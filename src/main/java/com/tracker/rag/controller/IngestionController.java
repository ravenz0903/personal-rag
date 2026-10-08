package com.tracker.rag.controller;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.dto.IngestResult;
import com.tracker.rag.model.DocumentRecord;
import com.tracker.rag.repository.DocumentRecordRepository;
import com.tracker.rag.service.DocumentIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/ingest")
@CrossOrigin(origins = "*")
public class IngestionController {

    private final DocumentIngestionService ingestionService;
    private final DocumentRecordRepository documentRecordRepository;

    public IngestionController(
            DocumentIngestionService ingestionService,
            DocumentRecordRepository documentRecordRepository
    ) {
        this.ingestionService = ingestionService;
        this.documentRecordRepository = documentRecordRepository;
    }

    @PostMapping("/upload")
    public ResponseEntity<IngestResult> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam("domain") String domain
    ) throws IOException {
        DomainScope scope = DomainScope.parse(domain);
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf";
        byte[] bytes = file.getBytes();
        IngestResult result = ingestionService.ingestPdf(filename, scope, bytes);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/markdown")
    public ResponseEntity<IngestResult> ingestMarkdown(@RequestBody MarkdownIngestRequest request) {
        DomainScope scope = DomainScope.parse(request.domain());
        IngestResult result = ingestionService.ingestMarkdown(request.path(), scope, request.content());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/documents")
    public ResponseEntity<List<DocumentRecord>> getDocuments(@RequestParam(required = false) String domain) {
        if (domain != null && !domain.isBlank()) {
            DomainScope scope = DomainScope.parse(domain);
            return ResponseEntity.ok(documentRecordRepository.findByDomain(scope));
        }
        return ResponseEntity.ok(documentRecordRepository.findAll());
    }

    public record MarkdownIngestRequest(String path, String domain, String content) {}
}
