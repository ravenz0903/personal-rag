package com.tracker.rag.repository.vector;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.UnscopedQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Embedded vector store implementation for H2 and in-memory environments.
 * Stores vectors as IEEE 754 float binary arrays in H2 VARBINARY columns and
 * evaluates cosine similarity using a bounded min-heap priority queue.
 */
public class EmbeddedVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedVectorStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final Map<UUID, VectorChunk> inMemoryStore;

    public EmbeddedVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.inMemoryStore = (jdbcTemplate == null) ? new ConcurrentHashMap<>() : null;
        if (jdbcTemplate != null) {
            initializeSchema();
        } else {
            log.info("[EmbeddedVectorStore] Running in pure in-memory mode (no JdbcTemplate provided).");
        }
    }

    /**
     * Default constructor for isolated in-memory unit tests.
     */
    public EmbeddedVectorStore() {
        this(null);
    }

    private void initializeSchema() {
        log.info("[EmbeddedVectorStore] Initializing H2 vector_chunks table...");
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS vector_chunks (
                id UUID PRIMARY KEY,
                document_id VARCHAR(255) NOT NULL,
                domain VARCHAR(32) NOT NULL,
                page_number INT NOT NULL,
                heading_path VARCHAR(1024),
                content CLOB NOT NULL,
                embedding_bytes VARBINARY(4096) NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
            );
        """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_h2_vc_doc_id ON vector_chunks (document_id);");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_h2_vc_domain ON vector_chunks (domain);");
        log.info("[EmbeddedVectorStore] H2 schema initialized successfully.");
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

        if (inMemoryStore != null) {
            for (VectorChunk chunk : chunks) {
                inMemoryStore.put(chunk.id(), chunk);
            }
            return;
        }

        String sql = """
            MERGE INTO vector_chunks (id, document_id, domain, page_number, heading_path, content, embedding_bytes)
            KEY (id)
            VALUES (?, ?, ?, ?, ?, ?, ?);
        """;

        jdbcTemplate.batchUpdate(sql, chunks, chunks.size(), (ps, chunk) -> {
            ps.setObject(1, chunk.id());
            ps.setString(2, chunk.documentId());
            ps.setString(3, chunk.domain().name());
            ps.setInt(4, chunk.pageNumber());
            ps.setString(5, chunk.headingPath());
            ps.setString(6, chunk.content());
            ps.setBytes(7, VectorSerializationUtils.floatsToBytes(chunk.embedding()));
        });
    }

    @Override
    @Transactional
    public void deleteByDocumentId(String documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (inMemoryStore != null) {
            inMemoryStore.values().removeIf(chunk -> documentId.equals(chunk.documentId()));
            return;
        }
        jdbcTemplate.update("DELETE FROM vector_chunks WHERE document_id = ?", documentId);
    }

    @Override
    @Transactional
    public void deleteById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        if (inMemoryStore != null) {
            inMemoryStore.remove(id);
            return;
        }
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

        List<VectorChunk> candidates = fetchCandidates(validatedScopes);
        return rankCandidates(candidates, queryEmbedding, topK);
    }

    private List<VectorChunk> fetchCandidates(Set<DomainScope> scopes) {
        if (inMemoryStore != null) {
            return inMemoryStore.values().stream()
                .filter(chunk -> scopes.contains(chunk.domain()))
                .toList();
        }

        String domainPlaceholders = scopes.stream().map(s -> "?").collect(Collectors.joining(", "));
        String sql = "SELECT id, document_id, domain, page_number, heading_path, content, embedding_bytes " +
                     "FROM vector_chunks WHERE domain IN (" + domainPlaceholders + ")";

        Object[] params = scopes.stream().map(DomainScope::name).toArray();
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapVectorChunk(rs), params);
    }

    /**
     * Bounded min-heap priority queue selection of top-K results.
     */
    private List<ScoredChunk> rankCandidates(List<VectorChunk> candidates, float[] queryEmbedding, int topK) {
        if (candidates.isEmpty()) return List.of();

        // Min-heap ordered by ascending score (natural ordering of ScoredChunk)
        PriorityQueue<ScoredChunk> minHeap = new PriorityQueue<>(topK);

        for (VectorChunk candidate : candidates) {
            double similarity = VectorSerializationUtils.cosineSimilarity(queryEmbedding, candidate.embedding());
            ScoredChunk scored = new ScoredChunk(candidate, similarity);

            if (minHeap.size() < topK) {
                minHeap.offer(scored);
            } else if (similarity > minHeap.peek().score()) {
                minHeap.poll();
                minHeap.offer(scored);
            }
        }

        // Extract from min-heap and sort descending
        List<ScoredChunk> results = new ArrayList<>(minHeap);
        results.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return results;
    }

    private VectorChunk mapVectorChunk(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String documentId = rs.getString("document_id");
        DomainScope domain = DomainScope.valueOf(rs.getString("domain"));
        int pageNumber = rs.getInt("page_number");
        String headingPath = rs.getString("heading_path");
        String content = rs.getString("content");
        byte[] embeddingBytes = rs.getBytes("embedding_bytes");

        float[] embedding = VectorSerializationUtils.bytesToFloats(embeddingBytes);
        return new VectorChunk(id, documentId, domain, pageNumber, headingPath, content, embedding);
    }

    @Override
    public long count() {
        if (inMemoryStore != null) {
            return inMemoryStore.size();
        }
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM vector_chunks", Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public long countByDomain(DomainScope scope) {
        if (scope == null) {
            throw new UnscopedQueryException("Domain scope must be explicitly specified (fail-closed)");
        }
        if (inMemoryStore != null) {
            return inMemoryStore.values().stream()
                .filter(chunk -> chunk.domain() == scope)
                .count();
        }
        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_chunks WHERE domain = ?",
            Long.class,
            scope.name()
        );
        return count != null ? count : 0L;
    }
}
