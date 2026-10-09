package com.tracker.rag.controller;

import com.tracker.rag.service.ai.AiServiceException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(org.springframework.web.bind.MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).findFirst().orElse("Invalid record");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
    @ExceptionHandler(AiServiceException.class) ResponseEntity<?> ai(AiServiceException e) {
        return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage()));
    }
    @ExceptionHandler({IllegalArgumentException.class, com.tracker.rag.domain.UnscopedQueryException.class,
            com.tracker.rag.dto.ExpiredConfirmationCardException.class, com.tracker.rag.dto.EmptyDocumentException.class,
            com.tracker.rag.dto.DocumentParsingException.class})
    ResponseEntity<?> invalid(RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    @ExceptionHandler({com.tracker.rag.dto.CardAlreadyConfirmedException.class, org.springframework.dao.DataIntegrityViolationException.class})
    ResponseEntity<?> conflict(RuntimeException e) { return ResponseEntity.status(409).body(Map.of("message", "This record already exists or the action has already been confirmed.")); }
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "Request failed" : e.getReason()));
    }
}
