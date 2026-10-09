package com.tracker.rag.service.retrieval;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable posting record representing a term occurrence within an indexed chunk.
 * Contains chunk identity, provenance metadata, term frequency, and token position offsets.
 */
public record Posting(
        UUID chunkId,
        String documentId,
        int pageNumber,
        String headingPath,
        String content,
        int termFrequency,
        List<Integer> positions
) {
    public Posting {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (termFrequency <= 0) {
            throw new IllegalArgumentException("termFrequency must be positive: " + termFrequency);
        }
        positions = (positions == null) ? List.of() : List.copyOf(positions);
    }
}
