package com.tracker.rag.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record HabitLogRecord(
        UUID id,
        UUID habitId,
        LocalDate logDate,
        Instant loggedAt,
        int unitsCompleted,
        String notes
) {
    public HabitLogRecord {
        Objects.requireNonNull(habitId, "habitId cannot be null");
        Objects.requireNonNull(logDate, "logDate cannot be null");
        if (loggedAt == null) {
            loggedAt = Instant.now();
        }
        if (unitsCompleted < 1) {
            throw new IllegalArgumentException("unitsCompleted must be at least 1: " + unitsCompleted);
        }
    }

    public static HabitLogRecord create(UUID habitId, LocalDate logDate, int units) {
        return new HabitLogRecord(UUID.randomUUID(), habitId, logDate, Instant.now(), units, null);
    }
}

