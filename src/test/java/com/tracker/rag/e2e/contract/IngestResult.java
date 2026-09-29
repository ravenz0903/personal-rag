package com.tracker.rag.e2e.contract;

public record IngestResult(
        String documentId,
        String status, // CREATED, SKIPPED_UNCHANGED, UPDATED
        int chunkCount,
        String fileHash,
        String message
) {
    public static IngestResult created(String docId, int count, String hash) {
        return new IngestResult(docId, "CREATED", count, hash, "Document successfully indexed");
    }

    public static IngestResult skipped(String docId, String hash) {
        return new IngestResult(docId, "SKIPPED_UNCHANGED", 0, hash, "File unchanged based on SHA-256 checksum");
    }

    public static IngestResult updated(String docId, int count, String hash) {
        return new IngestResult(docId, "UPDATED", count, hash, "Document re-ingested transactionally");
    }
}
