package com.tracker.rag.e2e.contract;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public interface HabitStreakEngine {
    StreakResult computeStreak(
            List<LocalDate> completionDates,
            LocalDate referenceDate,
            ZoneId userZoneId
    );

    StreakResult computeStreakWithFrequency(
            List<LocalDate> completionDates,
            LocalDate referenceDate,
            ZoneId userZoneId,
            HabitFrequency frequency,
            int customDaysMask
    );
}
