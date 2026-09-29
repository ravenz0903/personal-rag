package com.tracker.rag.e2e.contract;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record HybridSearchResult(
        String query,
        DomainScope domain,
        List<Citation> citations
) {
    public HybridSearchResult {
        Objects.requireNonNull(query, "query cannot be null");
        Objects.requireNonNull(domain, "domain cannot be null");
        if (citations == null) {
            citations = Collections.emptyList();
        } else {
            citations = List.copyOf(citations);
        }
    }
}
