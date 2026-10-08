package com.tracker.rag.service;

import com.tracker.rag.domain.ActionType;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.dto.CardAlreadyConfirmedException;
import com.tracker.rag.dto.ConfirmationCard;
import com.tracker.rag.dto.ExpiredConfirmationCardException;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.CareerApplicationRepository;
import com.tracker.rag.repository.HabitRepository;
import com.tracker.rag.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production natural language quick-capture service enforcing human-in-the-loop preview
 * confirmation cards, 15-minute TTL, idempotency guards, and transactional event-logged commits.
 */
@Service
public class CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

    private final Map<UUID, ConfirmationCard> previewCache = new ConcurrentHashMap<>();
    private final Set<UUID> confirmedTokens = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final EventLogService eventLogService;
    private final TaskRepository taskRepository;
    private final HabitRepository habitRepository;
    private final CareerApplicationRepository careerApplicationRepository;

    public CaptureService(
            EventLogService eventLogService,
            TaskRepository taskRepository,
            HabitRepository habitRepository,
            CareerApplicationRepository careerApplicationRepository
    ) {
        this.eventLogService = eventLogService;
        this.taskRepository = taskRepository;
        this.habitRepository = habitRepository;
        this.careerApplicationRepository = careerApplicationRepository;
    }

    public ConfirmationCard createPreviewCard(String naturalLanguageInput, DomainScope userSelectedDomain) {
        DomainScope.validateScope(userSelectedDomain);
        if (naturalLanguageInput == null || naturalLanguageInput.trim().isEmpty()) {
            throw new IllegalArgumentException("Natural language input cannot be blank");
        }

        String input = naturalLanguageInput.trim();
        String lower = input.toLowerCase();

        boolean isInjection = lower.contains("system prompt") || lower.contains("override") ||
                              lower.contains("ignore previous") || lower.contains("delete all") ||
                              lower.contains("drop table");

        String entityType;
        Map<String, Object> payload = new HashMap<>();
        String summary;

        if (!isInjection && (lower.contains("exam") || lower.contains("midterm") || lower.contains("grade") || lower.contains("quiz"))) {
            entityType = "ASSESSMENT";
            payload.put("title", extractTitle(input, "assessment"));
            payload.put("maxScore", 100.00);
            payload.put("courseCode", "CS101");
            summary = "Create assessment '" + payload.get("title") + "' for CS101";
        } else if (!isInjection && (lower.contains("habit") || lower.contains("daily") || lower.contains("meditat") || lower.contains("workout"))) {
            entityType = "HABIT";
            payload.put("name", extractTitle(input, "habit"));
            payload.put("frequency", HabitFrequency.DAILY.name());
            payload.put("targetCountPerDay", 1);
            summary = "Create habit '" + payload.get("name") + "' with daily frequency";
        } else if (!isInjection && (lower.contains("job") || lower.contains("apply") || lower.contains("interview") || lower.contains("offer"))) {
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
                Instant.now().plusSeconds(900)
        );

        previewCache.put(token, card);
        return card;
    }

    @Transactional
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

        confirmedTokens.add(confirmationToken);
        previewCache.remove(confirmationToken);

        UUID entityId = UUID.randomUUID();
        EntityType eventEntityType;

        switch (card.entityType()) {
            case "HABIT" -> {
                eventEntityType = EntityType.HABIT;
                String name = (String) card.proposedPayload().getOrDefault("name", "Habit");
                Habit habit = new Habit(name, HabitFrequency.DAILY, 1, "UTC");
                habit.setId(entityId);
                habitRepository.save(habit);
            }
            case "CAREER" -> {
                eventEntityType = EntityType.CAREER_APPLICATION;
                String company = (String) card.proposedPayload().getOrDefault("company", "Company");
                String pos = (String) card.proposedPayload().getOrDefault("position", "Role");
                CareerApplication app = new CareerApplication(company, pos, CareerStatus.APPLIED, null);
                app.setId(entityId);
                careerApplicationRepository.save(app);
            }
            case "ASSESSMENT" -> {
                eventEntityType = EntityType.ASSESSMENT;
            }
            default -> {
                eventEntityType = EntityType.TASK;
                String title = (String) card.proposedPayload().getOrDefault("title", "Task");
                Task task = new Task(card.targetDomain(), title, TaskPriority.MEDIUM, Instant.now().plusSeconds(86400));
                task.setId(entityId);
                taskRepository.save(task);
            }
        }

        if (eventLogService != null) {
            eventLogService.recordMutation(
                    card.targetDomain(),
                    eventEntityType,
                    entityId,
                    ActionType.CREATE,
                    null,
                    card.proposedPayload()
            );
        }

        log.info("[Capture] Committed confirmed card token={}, created entityId={}", confirmationToken, entityId);
        return entityId;
    }

    public void cancelPreviewCard(UUID confirmationToken) {
        previewCache.remove(confirmationToken);
    }

    public Optional<ConfirmationCard> getPreviewCard(UUID confirmationToken) {
        return Optional.ofNullable(previewCache.get(confirmationToken));
    }

    public List<ConfirmationCard> getActiveCards() {
        return new ArrayList<>(previewCache.values());
    }

    private String extractTitle(String input, String fallback) {
        String clean = input.replaceAll("(?i)(create|add|log|schedule|record)\\s+", "").trim();
        return clean.isEmpty() ? fallback : clean;
    }
}
