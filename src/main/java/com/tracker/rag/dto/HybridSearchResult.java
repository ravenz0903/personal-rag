package com.tracker.rag.dto;

import com.tracker.rag.domain.DomainScope;

import java.util.List;

public record HybridSearchResult(
        String query,
        DomainScope domain,
        List<Citation> citations
) {}
