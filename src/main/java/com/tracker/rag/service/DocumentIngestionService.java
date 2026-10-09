package com.tracker.rag.service;

import com.tracker.rag.repository.DocumentRecordRepository;
import com.tracker.rag.repository.vector.VectorStore;
import com.tracker.rag.service.retrieval.InvertedIndexService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Production document ingestion engine implementing page-aware PDF extraction (PDFBox 3.0.3),
 * hierarchical Markdown heading tree parsing, SHA-256 deduplication, and staged parallel virtual-thread
 * embedding generation.
 *
 * Extends com.tracker.rag.service.ingestion.DocumentIngestionService for 100% backward compatibility
 * across all existing callers, test suites, and controllers.
 */
@Service
public class DocumentIngestionService extends com.tracker.rag.service.ingestion.DocumentIngestionService {

    @Autowired
    public DocumentIngestionService(
            VectorStore vectorStore,
            DocumentRecordRepository documentRecordRepository,
            OllamaClientService ollamaClientService,
            @Autowired(required = false) InvertedIndexService invertedIndexService
    ) {
        super(vectorStore, documentRecordRepository, ollamaClientService, invertedIndexService);
    }

    public DocumentIngestionService(
            VectorStore vectorStore,
            DocumentRecordRepository documentRecordRepository,
            OllamaClientService ollamaClientService
    ) {
        super(vectorStore, documentRecordRepository, ollamaClientService, null);
    }
}
