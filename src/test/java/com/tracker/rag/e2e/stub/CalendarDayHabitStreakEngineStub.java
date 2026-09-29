package com.tracker.rag.e2e.stub;

import com.tracker.rag.e2e.contract.HabitFrequency;
import com.tracker.rag.e2e.contract.HabitStreakEngine;
import com.tracker.rag.e2e.contract.StreakResult;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Calendar-day anchored streak engine resilient to 23h Spring-forward and
 * 25h Fall-back DST transitions per spec_report.md § 5.2 and PROJECT.md F09.
 */
public class CalendarDayHabitStreakEngineStub implements HabitStreakEngine {

    @Override
    public StreakResult computeStreak(
            List<LocalDate> completionDates,
            LocalDate referenceDate,
            ZoneId userZoneId
    ) {
        return computeStreakWithFrequency(completionDates, referenceDate, userZoneId, HabitFrequency.DAILY, 127);
    }

    @Override
    public StreakResult computeStreakWithFrequency(
            List<LocalDate> completionDates,
            LocalDate referenceDate,
            ZoneId userZoneId,
            HabitFrequency frequency,
            int customDaysMask
    ) {
        if (referenceDate == null) {
            referenceDate = LocalDate.now(userZoneId != null ? userZoneId : ZoneId.of("UTC"));
        }
        if (frequency == null) {
            frequency = HabitFrequency.DAILY;
        }

        Set<LocalDate> completedSet = (completionDates == null || completionDates.isEmpty())
                ? Collections.emptySet()
                : new HashSet<>(completionDates);

        if (completedSet.isEmpty()) {
            return StreakResult.zero();
        }

        LocalDate today = referenceDate;
        boolean completedToday = completedSet.contains(today);
        LocalDate lastCompletedDate = completedSet.stream().max(LocalDate::compareTo).orElse(null);

        // 1. Calculate Current Streak
        LocalDate evalDate;
        boolean todayRequired = frequency.isRequiredDay(today, customDaysMask);

        if (completedToday) {
            evalDate = today;
        } else if (!todayRequired) {
            evalDate = getPreviousRequiredDay(today, frequency, customDaysMask);
        } else {
            // Today required but not yet completed: grace period check
            LocalDate prevRequired = getPreviousRequiredDay(today, frequency, customDaysMask);
            if (completedSet.contains(prevRequired)) {
                evalDate = prevRequired;
            } else {
                evalDate = null; // Streak broken
            }
        }

        int currentStreak = 0;
        if (evalDate != null) {
            LocalDate curr = evalDate;
            while (true) {
                if (frequency.isRequiredDay(curr, customDaysMask)) {
                    if (completedSet.contains(curr)) {
                        currentStreak++;
                    } else {
                        break;
                    }
                }
                curr = curr.minusDays(1);
            }
        }

        // 2. Calculate Longest Streak
        int longestStreak = calculateLongestStreak(completedSet, frequency, customDaysMask);
        if (currentStreak > longestStreak) {
            longestStreak = currentStreak;
        }

        return new StreakResult(
                currentStreak,
                longestStreak,
                lastCompletedDate,
                completedToday,
                completedToday || (evalDate != null && currentStreak > 0)
        );
    }

    private LocalDate getPreviousRequiredDay(LocalDate fromDate, HabitFrequency freq, int mask) {
        LocalDate prev = fromDate.minusDays(1);
        while (!freq.isRequiredDay(prev, mask)) {
            prev = prev.minusDays(1);
        }
        return prev;
    }

    private int calculateLongestStreak(Set<LocalDate> completedSet, HabitFrequency freq, int mask) {
        if (completedSet.isEmpty()) return 0;

        List<LocalDate> sortedDates = new ArrayList<>(completedSet);
        Collections.sort(sortedDates);

        LocalDate minDate = sortedDates.get(0);
        LocalDate maxDate = sortedDates.get(sortedDates.size() - 1);

        int maxStreak = 0;
        int runningStreak = 0;

        LocalDate curr = minDate;
        while (!curr.isAfter(maxDate)) {
            if (freq.isRequiredDay(curr, mask)) {
                if (completedSet.contains(curr)) {
                    runningStreak++;
                    maxStreak = Math.max(maxStreak, runningStreak);
                } else {
                    runningStreak = 0;
                }
            }
            curr = curr.plusDays(1);
        }

        return maxStreak;
    }
}
