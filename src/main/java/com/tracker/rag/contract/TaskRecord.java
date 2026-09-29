package com.tracker.rag.contract;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskRecord(
        UUID id,
        DomainScope domain,
        String title,
        String description,
        Instant dueDate,
        TaskPriority priority,
        TaskStatus status,
        Instant completedAt,
        UUID parentTaskId,
        UUID courseId,
        UUID careerApplicationId
) {
    public TaskRecord {
        Objects.requireNonNull(domain, "Domain cannot be null");
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Task title cannot be null or blank");
        }
        if (priority == null) {
            priority = TaskPriority.MEDIUM;
        }
        if (status == null) {
            status = TaskStatus.PENDING;
        }
        if (status == TaskStatus.COMPLETED && completedAt == null) {
            completedAt = Instant.now();
        } else if (status != TaskStatus.COMPLETED) {
            completedAt = null;
        }
    }

    public TaskRecord withStatus(TaskStatus newStatus) {
        if (!this.status.canTransitionTo(newStatus)) {
            throw new InvalidStateTransitionException(
                    "Cannot transition task from " + this.status + " to " + newStatus
            );
        }
        Instant newCompletedAt = (newStatus == TaskStatus.COMPLETED) ? Instant.now() : null;
        return new TaskRecord(
                id, domain, title, description, dueDate, priority, newStatus, newCompletedAt,
                parentTaskId, courseId, careerApplicationId
        );
    }

    public static TaskRecord create(DomainScope domain, String title, TaskPriority priority, Instant dueDate) {
        return new TaskRecord(
                UUID.randomUUID(), domain, title, null, dueDate, priority, TaskStatus.PENDING, null, null, null, null
        );
    }
}

