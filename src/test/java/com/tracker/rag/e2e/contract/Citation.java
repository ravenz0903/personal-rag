package com.tracker.rag.e2e.contract;

public record Citation(
        String sourceFile,
        Integer pageNumber,
        String headingPath,
        String snippet,
        double rrfScore,
        int denseRank,
        int sparseRank
) {
    public Citation(String sourceFile, int pageNumber, String headingPath, String snippet, double rrfScore) {
        this(sourceFile, pageNumber, headingPath, snippet, rrfScore, 0, 0);
    }

    public String toSourceTag() {
        if (pageNumber != null && pageNumber > 0) {
            return String.format("[%s, Page %d]", sourceFile, pageNumber);
        } else if (headingPath != null && !headingPath.isBlank()) {
            return String.format("[%s, Section: %s]", sourceFile, headingPath);
        } else {
            return String.format("[%s]", sourceFile);
        }
    }
}
