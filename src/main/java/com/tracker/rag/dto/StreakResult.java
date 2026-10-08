package com.tracker.rag.dto;

import java.time.LocalDate;

public record StreakResult(
        int currentStreak,
        int longestStreak,
        LocalDate lastCompletedDate,
        boolean completedToday,
        boolean streakActive
) {
    public static StreakResult zero() {
        return new StreakResult(0, 0, null, false, false);
    }
}
