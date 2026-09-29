package com.tracker.rag.e2e.stub;

import com.tracker.rag.e2e.contract.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Natural language quick-capture and confirmation preview card guardrail service
 * implementing strict human-in-the-loop commit flow per spec_report.md § 7 and PROJECT.md F13-F18.
 */
public class DeterministicCaptureServiceStub implements CaptureService {

    private final Map<UUID, ConfirmationCard> previewCache = new ConcurrentHashMap<>();
    private final Set<UUID> confirmedTokens = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, Object> persistedEntities = new ConcurrentHashMap<>();
    private final EventLogAndRollbackService eventLogService;

    public DeterministicCaptureServiceStub(EventLogAndRollbackService eventLogService) {
        this.eventLogService = eventLogService;
    }

    @Override
    public ConfirmationCard createPreviewCard(String naturalLanguageInput, DomainScope userSelectedDomain) {
        DomainScope.validateScope(userSelectedDomain);
        if (naturalLanguageInput == null || naturalLanguageInput.trim().isEmpty()) {
            throw new IllegalArgumentException("Natural language input cannot be blank");
        }

        String input = naturalLanguageInput.trim();

        // Security Guardrail: Check for prompt injection keywords
        // Prompt injections should NOT execute actions or bypass confirmation
        String entityType;
        Map<String, Object> payload = new HashMap<>();
        String summary;

        String lower = input.toLowerCase();
        boolean isInjection = lower.contains("system prompt") || lower.contains("override") ||
                              lower.contains("ignore previous") || lower.contains("delete all") ||
                              lower.contains("drop table");
        if (!isInjection && (lower.contains("exam") || lower.contains("midterm") || lower.contains("grade") || lower.contains("quiz"))) {
            entityType = "ASSESSMENT";
            payload.put("title", extractTitle(input, "assessment"));
            payload.put("maxScore", 100.00);
            payload.put("courseCode", "CS101");
            summary = "Create assessment '" + payload.get("title") + "' for CS101";
        } else if (lower.contains("habit") || lower.contains("daily") || lower.contains("meditat") || lower.contains("workout")) {
            entityType = "HABIT";
            payload.put("name", extractTitle(input, "habit"));
            payload.put("frequency", HabitFrequency.DAILY.name());
            payload.put("targetCountPerDay", 1);
            summary = "Create habit '" + payload.get("name") + "' with daily frequency";
        } else if (lower.contains("job") || lower.contains("apply") || lower.contains("interview") || lower.contains("offer")) {
            entityType = "CAREER";
            payload.put("company", extractTitle(input, "company"));
            payload.put("position", "Software Engineer");
            payload.put("status", CareerStatus.APPLIED.name());
            summary = "Log job application to " + payload.get("company");
        } else {
            entityType = "TASK";
            payload.put("title", input);
            payload.put("priority", TaskPriority.MEDIUM.name());
            payload.put("status", TaskStatus.PENDING.name());
            summary = "Create task '" + input + "' in domain " + userSelectedDomain;
        }

        // Schema validation: Validate title/name is non-empty
        String primaryTitle = (String) payload.getOrDefault("title", payload.getOrDefault("name", payload.get("company")));
        if (primaryTitle == null || primaryTitle.trim().isEmpty()) {
            throw new IllegalArgumentException("Draft title cannot be blank (Schema Validation Failure)");
        }

        UUID token = UUID.randomUUID();
        ConfirmationCard card = new ConfirmationCard(
                token,
                userSelectedDomain,
                entityType,
                payload,
                summary,
                Instant.now().plusSeconds(900) // 15-minute TTL
        );

        previewCache.put(token, card);
        // INVARIANT: Zero database writes occur at this step!
        return card;
    }

    @Override
    public synchronized Object commitConfirmation(UUID confirmationToken) {
        Objects.requireNonNull(confirmationToken, "Confirmation token cannot be null");

        if (confirmedTokens.contains(confirmationToken)) {
            throw new CardAlreadyConfirmedException("Confirmation token " + confirmationToken + " has already been committed.");
        }

        ConfirmationCard card = previewCache.get(confirmationToken);
        if (card == null) {
            throw new IllegalArgumentException("Unknown confirmation card token: " + confirmationToken);
        }

        if (card.isExpired()) {
            previewCache.remove(confirmationToken);
            throw new ExpiredConfirmationCardException("Confirmation card token " + confirmationToken + " has expired (>15 min TTL).");
        }

        // Mark confirmed (idempotency guard)
        confirmedTokens.add(confirmationToken);
        previewCache.remove(confirmationToken);

        UUID entityId = UUID.randomUUID();
        EntityType eventEntityType = switch (card.entityType()) {
            case "TASK" -> EntityType.TASK;
            case "ASSESSMENT" -> EntityType.ASSESSMENT;
            case "HABIT" -> EntityType.HABIT;
            case "CAREER" -> EntityType.CAREER_APPLICATION;
            default -> EntityType.TASK;
        };

        // Persist entity to database simulation
        persistedEntities.put(entityId, card.proposedPayload());

        // Emit append-only event log
        if (eventLogService != null) {
            eventLogService.recordMutation(
                    card.targetDomain(),
                    eventEntityType,
                    entityId,
                    ActionType.CREATE,
                    null,
                    card.proposedPayload().toString()
            );
        }

        return entityId;
    }

    @Override
    public void cancelPreviewCard(UUID confirmationToken) {
        previewCache.remove(confirmationToken);
    }

    public boolean isEntityPersisted(UUID entityId) {
        return persistedEntities.containsKey(entityId);
    }

    public int activePreviewCount() {
        return previewCache.size();
    }

    public int persistedEntitiesCount() {
        return persistedEntities.size();
    }

    private String extractTitle(String input, String fallback) {
        String clean = input.replaceAll("(?i)(create|add|log|schedule|record)\\s+", "").trim();
        return clean.isEmpty() ? fallback : clean;
    }
}
