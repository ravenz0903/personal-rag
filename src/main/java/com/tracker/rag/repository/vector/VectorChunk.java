package com.tracker.rag.repository.vector;

import com.tracker.rag.domain.DomainScope;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable representation of an indexed text chunk with its vector embedding
 * and provenance metadata.
 */
public record VectorChunk(
    UUID id,
    String documentId,
    DomainScope domain,
    int pageNumber,
    String headingPath,
    String content,
    float[] embedding
) {
    public VectorChunk {
        Objects.requireNonNull(id, "Chunk id must not be null");
        Objects.requireNonNull(documentId, "Document id must not be null");
        Objects.requireNonNull(domain, "Domain scope must not be null (fail-closed)");
        Objects.requireNonNull(content, "Content must not be null");
        Objects.requireNonNull(embedding, "Embedding must not be null");

        if (pageNumber < 0) {
            throw new IllegalArgumentException("Page number cannot be negative: " + pageNumber);
        }
        if (embedding.length == 0) {
            throw new IllegalArgumentException("Embedding array must not be empty");
        }
        // Clone embedding to preserve immutability
        embedding = embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VectorChunk that)) return false;
        return pageNumber == that.pageNumber &&
               Objects.equals(id, that.id) &&
               Objects.equals(documentId, that.documentId) &&
               domain == that.domain &&
               Objects.equals(headingPath, that.headingPath) &&
               Objects.equals(content, that.content) &&
               Arrays.equals(embedding, that.embedding);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, documentId, domain, pageNumber, headingPath, content);
        result = 31 * result + Arrays.hashCode(embedding);
        return result;
    }

    @Override
    public String toString() {
        return "VectorChunk{" +
               "id=" + id +
               ", documentId='" + documentId + '\'' +
               ", domain=" + domain +
               ", pageNumber=" + pageNumber +
               ", headingPath='" + headingPath + '\'' +
               ", contentLength=" + content.length() +
               ", embeddingDimensions=" + embedding.length +
               '}';
    }
}
