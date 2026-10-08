package com.tracker.rag.dto;

public record IngestResult(
        String filePath,
        int chunksCreated,
        String contentHash,
        String status
) {
    public static IngestResult created(String filePath, int count, String hash) {
        return new IngestResult(filePath, count, hash, "CREATED");
    }

    public static IngestResult updated(String filePath, int count, String hash) {
        return new IngestResult(filePath, count, hash, "UPDATED");
    }

    public static IngestResult skipped(String filePath, String hash) {
        return new IngestResult(filePath, 0, hash, "SKIPPED");
    }
}
