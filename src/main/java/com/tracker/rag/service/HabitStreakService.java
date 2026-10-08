package com.tracker.rag.service;

import com.tracker.rag.dto.StreakResult;
import com.tracker.rag.model.HabitFrequency;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Calendar-day anchored streak engine resilient to 23h Spring-forward and
 * 25h Fall-back DST transitions per spec_report.md § 5.2 and PROJECT.md F09.
 */
@Service
public class HabitStreakService {

    public StreakResult computeStreak(
            List<LocalDate> completionDates,
            LocalDate referenceDate,
            ZoneId userZoneId
    ) {
        return computeStreakWithFrequency(completionDates, referenceDate, userZoneId, HabitFrequency.DAILY, 127);
    }

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

        LocalDate evalDate;
        boolean todayRequired = isRequiredDay(frequency, today, customDaysMask);

        if (completedToday) {
            evalDate = today;
        } else if (!todayRequired) {
            evalDate = getPreviousRequiredDay(today, frequency, customDaysMask);
        } else {
            LocalDate prevRequired = getPreviousRequiredDay(today, frequency, customDaysMask);
            if (completedSet.contains(prevRequired)) {
                evalDate = prevRequired;
            } else {
                evalDate = null;
            }
        }

        int currentStreak = 0;
        if (evalDate != null) {
            LocalDate curr = evalDate;
            while (true) {
                if (isRequiredDay(frequency, curr, customDaysMask)) {
                    if (completedSet.contains(curr)) {
                        currentStreak++;
                    } else {
                        break;
                    }
                }
                curr = curr.minusDays(1);
            }
        }

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

    private boolean isRequiredDay(HabitFrequency freq, LocalDate date, int mask) {
        if (freq == HabitFrequency.DAILY) return true;
        if (freq == HabitFrequency.WEEKDAYS) {
            int dow = date.getDayOfWeek().getValue();
            return dow >= 1 && dow <= 5;
        }
        if (freq == HabitFrequency.WEEKENDS) {
            int dow = date.getDayOfWeek().getValue();
            return dow == 6 || dow == 7;
        }
        if (freq == HabitFrequency.CUSTOM_DAYS) {
            int bit = 1 << (date.getDayOfWeek().getValue() - 1);
            return (mask & bit) != 0;
        }
        return true;
    }

    private LocalDate getPreviousRequiredDay(LocalDate fromDate, HabitFrequency freq, int mask) {
        LocalDate prev = fromDate.minusDays(1);
        while (!isRequiredDay(freq, prev, mask)) {
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
            if (isRequiredDay(freq, curr, mask)) {
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
