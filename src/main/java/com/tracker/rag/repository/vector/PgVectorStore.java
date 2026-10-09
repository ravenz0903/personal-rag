package com.tracker.rag.repository.vector;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.UnscopedQueryException;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PostgreSQL 16 vector store implementation utilizing the native pgvector extension,
 * HNSW indexing, and the cosine distance operator (<=>).
 */
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    private final JdbcTemplate jdbcTemplate;

    public PgVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate must not be null");
        initializeSchema();
    }

    private void initializeSchema() {
        try {
            log.info("[PgVectorStore] Initializing pgvector extension and vector_chunks table...");
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector;");
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS vector_chunks (
                    id UUID PRIMARY KEY,
                    document_id VARCHAR(255) NOT NULL,
                    domain VARCHAR(32) NOT NULL,
                    page_number INT NOT NULL,
                    heading_path VARCHAR(1024),
                    content TEXT NOT NULL,
                    embedding vector(768) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                );
            """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_vc_doc_id ON vector_chunks (document_id);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_vc_domain ON vector_chunks (domain);");
            // HNSW index for sub-millisecond approximate nearest neighbor search
            jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_vc_embedding_hnsw 
                ON vector_chunks USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64);
            """);
            log.info("[PgVectorStore] Schema initialized successfully.");
        } catch (DataAccessException e) {
            log.error("[PgVectorStore] Failed to initialize PostgreSQL vector schema: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    @Transactional
    public void save(VectorChunk chunk) {
        saveAll(List.of(chunk));
    }

    @Override
    @Transactional
    public void saveAll(List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;

        String sql = """
            INSERT INTO vector_chunks (id, document_id, domain, page_number, heading_path, content, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?::vector)
            ON CONFLICT (id) DO UPDATE SET
                document_id = EXCLUDED.document_id,
                domain = EXCLUDED.domain,
                page_number = EXCLUDED.page_number,
                heading_path = EXCLUDED.heading_path,
                content = EXCLUDED.content,
                embedding = EXCLUDED.embedding;
        """;

        jdbcTemplate.batchUpdate(sql, chunks, chunks.size(), (ps, chunk) -> {
            ps.setObject(1, chunk.id());
            ps.setString(2, chunk.documentId());
            ps.setString(3, chunk.domain().name());
            ps.setInt(4, chunk.pageNumber());
            ps.setString(5, chunk.headingPath());
            ps.setString(6, chunk.content());
            try {
                ps.setObject(7, toPgVectorObject(chunk.embedding()));
            } catch (SQLException e) {
                throw new RuntimeException("Error converting float array to PGobject vector", e);
            }
        });
    }

    @Override
    @Transactional
    public void deleteByDocumentId(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        jdbcTemplate.update("DELETE FROM vector_chunks WHERE document_id = ?", documentId);
    }

    @Override
    @Transactional
    public void deleteById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        jdbcTemplate.update("DELETE FROM vector_chunks WHERE id = ?", id);
    }

    @Override
    public List<ScoredChunk> similaritySearch(float[] queryEmbedding, DomainScope scope, int topK) {
        if (scope == null) {
            throw new UnscopedQueryException("Domain scope must be explicitly specified (fail-closed)");
        }
        return similaritySearch(queryEmbedding, Set.of(scope), topK);
    }

    @Override
    public List<ScoredChunk> similaritySearch(float[] queryEmbedding, Set<DomainScope> scopes, int topK) {
        if (scopes == null || scopes.isEmpty()) {
            throw new UnscopedQueryException("Domain scope must be explicitly specified (fail-closed)");
        }
        Set<DomainScope> validatedScopes = DomainScope.validateScopes(scopes);
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            throw new IllegalArgumentException("Query embedding must not be null or empty");
        }
        if (topK <= 0) {
            return List.of();
        }

        // Build domain IN (?, ?, ...) clause
        String domainPlaceholders = validatedScopes.stream().map(s -> "?").collect(Collectors.joining(", "));
        String sql = "SELECT id, document_id, domain, page_number, heading_path, content, " +
                     "embedding::text AS embedding_str, " +
                     "1.0 - (embedding <=> ?::vector) AS similarity_score " +
                     "FROM vector_chunks " +
                     "WHERE domain IN (" + domainPlaceholders + ") " +
                     "ORDER BY embedding <=> ?::vector ASC " +
                     "LIMIT ?";

        PGobject pgVector;
        try {
            pgVector = toPgVectorObject(queryEmbedding);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to construct PGobject query vector", e);
        }

        List<Object> params = new ArrayList<>();
        // 1. First parameter for score calculation: ?::vector
        params.add(pgVector);
        // 2. Domain scope parameters
        for (DomainScope scope : validatedScopes) {
            params.add(scope.name());
        }
        // 3. Second parameter for ORDER BY: ?::vector
        params.add(pgVector);
        // 4. LIMIT parameter
        params.add(topK);

        return jdbcTemplate.query(sql, (rs, rowNum) -> mapScoredChunk(rs), params.toArray());
    }

    @Override
    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM vector_chunks", Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public long countByDomain(DomainScope scope) {
        if (scope == null) {
            throw new UnscopedQueryException("Domain scope must be explicitly specified (fail-closed)");
        }
        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_chunks WHERE domain = ?",
            Long.class,
            scope.name()
        );
        return count != null ? count : 0L;
    }

    @Override
    public List<VectorChunk> findAll() {
        String sql = "SELECT id, document_id, domain, page_number, heading_path, content, embedding::text AS embedding_str FROM vector_chunks";
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapVectorChunk(rs));
    }

    @Override
    public List<VectorChunk> findAllByDomain(DomainScope domain) {
        if (domain == null) {
            throw new UnscopedQueryException("Domain scope must be explicitly specified (fail-closed)");
        }
        String sql = "SELECT id, document_id, domain, page_number, heading_path, content, embedding::text AS embedding_str FROM vector_chunks WHERE domain = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapVectorChunk(rs), domain.name());
    }

    private VectorChunk mapVectorChunk(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String documentId = rs.getString("document_id");
        DomainScope domain = DomainScope.valueOf(rs.getString("domain"));
        int pageNumber = rs.getInt("page_number");
        String headingPath = rs.getString("heading_path");
        String content = rs.getString("content");
        String embeddingStr = rs.getString("embedding_str");
        float[] embedding = parseVectorString(embeddingStr);
        return new VectorChunk(id, documentId, domain, pageNumber, headingPath, content, embedding);
    }

    private ScoredChunk mapScoredChunk(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String documentId = rs.getString("document_id");
        DomainScope domain = DomainScope.valueOf(rs.getString("domain"));
        int pageNumber = rs.getInt("page_number");
        String headingPath = rs.getString("heading_path");
        String content = rs.getString("content");
        String embeddingStr = rs.getString("embedding_str");
        double score = rs.getDouble("similarity_score");

        float[] embedding = parseVectorString(embeddingStr);

        VectorChunk chunk = new VectorChunk(id, documentId, domain, pageNumber, headingPath, content, embedding);
        return new ScoredChunk(chunk, score);
    }

    public static PGobject toPgVectorObject(float[] vector) throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("vector");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        obj.setValue(sb.toString());
        return obj;
    }

    public static float[] parseVectorString(String vectorStr) {
        if (vectorStr == null || vectorStr.isBlank()) return new float[0];
        String cleaned = vectorStr.trim();
        if (cleaned.startsWith("[")) cleaned = cleaned.substring(1);
        if (cleaned.endsWith("]")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        if (cleaned.isBlank()) return new float[0];

        String[] parts = cleaned.split(",");
        float[] floats = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            floats[i] = Float.parseFloat(parts[i].trim());
        }
        return floats;
    }
}
