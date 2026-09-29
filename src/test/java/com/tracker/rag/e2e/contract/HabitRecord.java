package com.tracker.rag.e2e.contract;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public record HabitRecord(
        UUID id,
        DomainScope domain,
        String name,
        HabitFrequency frequency,
        int targetCountPerDay,
        int customDaysMask,
        String timezone,
        boolean archived
) {
    public HabitRecord {
        Objects.requireNonNull(domain, "Domain cannot be null");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Habit name cannot be null or blank");
        }
        if (frequency == null) {
            frequency = HabitFrequency.DAILY;
        }
        if (targetCountPerDay < 1) {
            throw new IllegalArgumentException("Target count per day must be at least 1: " + targetCountPerDay);
        }
        if (timezone == null || timezone.trim().isEmpty()) {
            timezone = "UTC";
        }
        // Validate timezone string with ZoneId
        ZoneId.of(timezone);
    }

    public static HabitRecord create(String name, HabitFrequency frequency, int targetCount, String timezone) {
        return new HabitRecord(
                UUID.randomUUID(),
                DomainScope.PERSONAL,
                name,
                frequency,
                targetCount,
                127,
                timezone,
                false
        );
    }
}
