package com.tracker.rag.e2e.contract;

public interface HybridRetriever {
    HybridSearchResult search(String query, DomainScope domain, int topK);
}
