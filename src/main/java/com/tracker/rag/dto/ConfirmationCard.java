package com.tracker.rag.dto;

import com.tracker.rag.domain.DomainScope;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ConfirmationCard(
        UUID confirmationToken,
        DomainScope targetDomain,
        String entityType,
        Map<String, Object> proposedPayload,
        String summary,
        Instant expiresAt
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
