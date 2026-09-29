package com.tracker.rag.contract;

public interface HybridRetriever {
    HybridSearchResult search(String query, DomainScope domain, int topK);
}

