package com.tracker.rag.repository.vector;

import com.tracker.rag.domain.DomainScope;

import java.util.Objects;
import java.util.UUID;

/**
 * Encapsulates a VectorChunk and its similarity score relative to a query.
 * Natural ordering is ascending by score to facilitate bounded min-heap priority queues.
 */
public record ScoredChunk(
    VectorChunk chunk,
    double score
) implements Comparable<ScoredChunk> {

    public ScoredChunk {
        Objects.requireNonNull(chunk, "Chunk must not be null");
    }

    @Override
    public int compareTo(ScoredChunk other) {
        return Double.compare(this.score, other.score);
    }

    // Convenience delegation accessors
    public UUID id() {
        return chunk.id();
    }

    public String documentId() {
        return chunk.documentId();
    }

    public DomainScope domain() {
        return chunk.domain();
    }

    public int pageNumber() {
        return chunk.pageNumber();
    }

    public String headingPath() {
        return chunk.headingPath();
    }

    public String content() {
        return chunk.content();
    }

    public float[] embedding() {
        return chunk.embedding();
    }
}
