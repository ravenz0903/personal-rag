package com.tracker.rag.e2e.contract;

import java.util.Objects;
import java.util.UUID;

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
        if (id == null) {
            id = UUID.randomUUID();
        }
        Objects.requireNonNull(domain, "domain cannot be null");
        Objects.requireNonNull(content, "content cannot be null");
    }
}
