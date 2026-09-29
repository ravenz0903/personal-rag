package com.tracker.rag.e2e.contract;

public record ScoredChunk(
        VectorChunk chunk,
        double score
) {}
