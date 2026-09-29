package com.tracker.rag.e2e.contract;

import java.time.Instant;
import java.util.*;

public record ConfirmationCard(
        UUID confirmationToken,
        DomainScope targetDomain,
        String entityType, // TASK, ASSESSMENT, HABIT, CAREER
        Map<String, Object> proposedPayload,
        String previewSummary,
        Instant expiresAt
) {
    public ConfirmationCard(
            UUID confirmationToken,
            DomainScope targetDomain,
            String entityType,
            Map<String, Object> proposedPayload,
            String previewSummary,
            Instant expiresAt
    ) {
        this.confirmationToken = Objects.requireNonNull(confirmationToken, "confirmationToken cannot be null");
        this.targetDomain = Objects.requireNonNull(targetDomain, "targetDomain cannot be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType cannot be null");
        this.proposedPayload = proposedPayload == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(proposedPayload));
        this.previewSummary = previewSummary;
        this.expiresAt = expiresAt == null ? Instant.now().plusSeconds(900) : expiresAt;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
