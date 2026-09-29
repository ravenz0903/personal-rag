package com.tracker.rag.contract;

public record ScoredChunk(
        VectorChunk chunk,
        double score
) {}

