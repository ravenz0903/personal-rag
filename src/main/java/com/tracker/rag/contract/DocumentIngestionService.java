package com.tracker.rag.contract;

public interface DocumentIngestionService {
    IngestResult ingestPdf(String filePath, DomainScope domain, byte[] pdfBytes);

    IngestResult ingestMarkdown(String filePath, DomainScope domain, String markdownContent);
}

