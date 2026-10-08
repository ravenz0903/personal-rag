package com.tracker.rag.dto;

public record Citation(
        String documentId,
        Integer pageNumber,
        String headingPath,
        String content,
        double score,
        int denseRank,
        int sparseRank
) {}
