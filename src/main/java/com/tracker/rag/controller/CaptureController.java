package com.tracker.rag.controller;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.dto.ConfirmationCard;
import com.tracker.rag.model.EventLogEntry;
import com.tracker.rag.service.CaptureService;
import com.tracker.rag.service.RollbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/capture")
@CrossOrigin(origins = "*")
public class CaptureController {

    private final CaptureService captureService;
    private final RollbackService rollbackService;

    public CaptureController(CaptureService captureService, RollbackService rollbackService) {
        this.captureService = captureService;
        this.rollbackService = rollbackService;
    }

    @PostMapping("/preview")
    public ResponseEntity<ConfirmationCard> createPreviewCard(@RequestBody PreviewRequest request) {
        DomainScope scope = DomainScope.parse(request.domain());
        ConfirmationCard card = captureService.createPreviewCard(request.input(), scope);
        return ResponseEntity.ok(card);
    }

    @PostMapping("/commit/{token}")
    public ResponseEntity<Map<String, Object>> commitCard(@PathVariable UUID token) {
        Object entityId = captureService.commitConfirmation(token);
        return ResponseEntity.ok(Map.of(
                "status", "COMMITTED",
                "entityId", entityId,
                "confirmationToken", token
        ));
    }

    @DeleteMapping("/cancel/{token}")
    public ResponseEntity<Map<String, String>> cancelCard(@PathVariable UUID token) {
        captureService.cancelPreviewCard(token);
        return ResponseEntity.ok(Map.of("status", "CANCELLED", "confirmationToken", token.toString()));
    }

    @GetMapping("/active")
    public ResponseEntity<List<ConfirmationCard>> getActiveCards() {
        return ResponseEntity.ok(captureService.getActiveCards());
    }

    @PostMapping("/rollback/{eventId}")
    public ResponseEntity<EventLogEntry> rollbackEvent(@PathVariable UUID eventId) {
        EventLogEntry rolledBack = rollbackService.rollbackEvent(eventId);
        return ResponseEntity.ok(rolledBack);
    }

    @PostMapping("/rollback/last")
    public ResponseEntity<EventLogEntry> rollbackLast(@RequestParam String domain) {
        DomainScope scope = DomainScope.parse(domain);
        EventLogEntry rolledBack = rollbackService.rollbackLastMutation(scope);
        return ResponseEntity.ok(rolledBack);
    }

    public record PreviewRequest(String input, String domain) {}
}
