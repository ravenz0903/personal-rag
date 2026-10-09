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

public class IngestionController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.tracker.rag.service.storage.DocumentStorageService storage;

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
        String filename = safeFilename(file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf");
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) throw new IllegalArgumentException("Upload a PDF file");
        byte[] bytes = file.getBytes();
        if (bytes.length < 5 || !new String(bytes, 0, Math.min(bytes.length, 1024), java.nio.charset.StandardCharsets.ISO_8859_1).contains("%PDF-"))
            throw new IllegalArgumentException("This file is not a valid PDF");
        IngestResult result = ingestionService.ingestPdf(filename, scope, bytes);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/markdown")
    public ResponseEntity<IngestResult> ingestMarkdown(@RequestBody MarkdownIngestRequest request) {
        DomainScope scope = DomainScope.parse(request.domain());
        IngestResult result = ingestionService.ingestMarkdown(safeFilename(request.path()), scope, request.content());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/documents")
    public ResponseEntity<List<DocumentRecord>> getDocuments(@RequestParam(required = false) String domain) {
        if (domain != null && !domain.isBlank()) {
            DomainScope scope = DomainScope.parse(domain);
            return ResponseEntity.ok(documentRecordRepository.findByDomain(scope));
        }
        return ResponseEntity.ok(documentRecordRepository.findByDomainIn(DomainScope.defaultGeneralScope()));
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable java.util.UUID id, @RequestParam String domain) {
        DomainScope scope = DomainScope.parse(domain);
        DocumentRecord doc = documentRecordRepository.findById(id).filter(record -> record.getDomain() == scope)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Document not found in this domain"));
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(doc.getContentType()))
                .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment().filename(doc.getFileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(storage.read(doc.getStoragePath()));
    }

    private String safeFilename(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("A document filename is required");
        String name = path.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank() || name.length() > 200) throw new IllegalArgumentException("Invalid document filename");
        return name;
    }

    public record MarkdownIngestRequest(String path, String domain, String content) {}
}
