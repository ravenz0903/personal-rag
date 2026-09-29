package com.tracker.rag.e2e.contract;

import java.time.LocalDate;

public record StreakResult(
        int currentStreak,
        int longestStreak,
        LocalDate lastCompletedDate,
        boolean completedToday,
        boolean isActiveToday
) {
    public static StreakResult zero() {
        return new StreakResult(0, 0, null, false, false);
    }
}
