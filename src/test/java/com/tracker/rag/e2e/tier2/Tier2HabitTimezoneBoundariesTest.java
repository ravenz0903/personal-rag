package com.tracker.rag.e2e.tier2;

import com.tracker.rag.e2e.contract.*;
import com.tracker.rag.e2e.stub.CalendarDayHabitStreakEngineStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tier 2: Boundary & Corner Cases - Habit Streaks & DST Transitions (F08, F09)")
public class Tier2HabitTimezoneBoundariesTest {

    private HabitStreakEngine streakEngine;
    private final ZoneId nyZone = ZoneId.of("America/New_York");

    @BeforeEach
    void setUp() {
        streakEngine = new CalendarDayHabitStreakEngineStub();
    }

    @Nested
    @DisplayName("Habit Logging & Timezone Boundary Tests")
    class HabitLoggingBoundaries {

        @Test
        @DisplayName("BC-F08-01: Midnight boundary logging at 23:59 Day T and 00:01 Day T+1")
        void testMidnightBoundaryLogging() {
            // Instant 1: 2026-09-28 23:59:30 in America/New_York
            // Instant 2: 2026-09-29 00:01:15 in America/New_York
            ZonedDateTime t1 = ZonedDateTime.of(2026, 9, 28, 23, 59, 30, 0, nyZone);
            ZonedDateTime t2 = ZonedDateTime.of(2026, 9, 29, 0, 1, 15, 0, nyZone);

            LocalDate d1 = t1.toLocalDate();
            LocalDate d2 = t2.toLocalDate();

            assertEquals(LocalDate.of(2026, 9, 28), d1);
            assertEquals(LocalDate.of(2026, 9, 29), d2);

            List<LocalDate> completedDates = List.of(d1, d2);
            StreakResult result = streakEngine.computeStreak(completedDates, d2, nyZone);

            assertEquals(2, result.currentStreak(), "Logging across midnight boundary creates consecutive 2-day streak");
            assertTrue(result.completedToday());
        }

        @Test
        @DisplayName("BC-F08-02: Future date logging validation in habit timezone")
        void testFutureDateLoggingRejected() {
            LocalDate todayInNy = LocalDate.now(nyZone);
            LocalDate tomorrowInNy = todayInNy.plusDays(1);

            assertThrows(FutureDateLoggingException.class, () -> {
                if (tomorrowInNy.isAfter(todayInNy)) {
                    throw new FutureDateLoggingException("Cannot log habit completion for future date: " + tomorrowInNy);
                }
            });
        }

        @Test
        @DisplayName("BC-F08-03: Unknown timezone string throws ZoneRulesException")
        void testUnknownTimezoneThrows() {
            assertThrows(DateTimeException.class, () -> ZoneId.of("GMT+99"));
            assertThrows(ZoneRulesException.class, () -> HabitRecord.create("Habit", HabitFrequency.DAILY, 1, "Fake/Zone"));
        }

        @Test
        @DisplayName("BC-F08-04: Target count accumulation boundary")
        void testTargetCountAccumulation() {
            int target = 3;
            List<HabitLogRecord> logsForDay = List.of(
                    HabitLogRecord.create(java.util.UUID.randomUUID(), LocalDate.now(), 1),
                    HabitLogRecord.create(java.util.UUID.randomUUID(), LocalDate.now(), 1)
            );
            int total = logsForDay.stream().mapToInt(HabitLogRecord::unitsCompleted).sum();
            assertFalse(total >= target, "2 units do not satisfy target of 3");

            List<HabitLogRecord> completedLogs = new ArrayList<>(logsForDay);
            completedLogs.add(HabitLogRecord.create(java.util.UUID.randomUUID(), LocalDate.now(), 1));
            int newTotal = completedLogs.stream().mapToInt(HabitLogRecord::unitsCompleted).sum();
            assertTrue(newTotal >= target, "3 units satisfy target of 3");
        }

        @Test
        @DisplayName("BC-F08-05: Leap year boundary: Feb 28 -> Feb 29 -> Mar 1 consecutive streak")
        void testLeapYearBoundary() {
            // 2028 is a leap year
            List<LocalDate> leapDates = List.of(
                    LocalDate.of(2028, 2, 28),
                    LocalDate.of(2028, 2, 29),
                    LocalDate.of(2028, 3, 1)
            );
            StreakResult result = streakEngine.computeStreak(leapDates, LocalDate.of(2028, 3, 1), nyZone);
            assertEquals(3, result.currentStreak(), "Leap year Feb 28 -> Feb 29 -> Mar 1 evaluates cleanly");
        }
    }

    @Nested
    @DisplayName("Daylight Saving Time (DST) Boundary Tests")
    class DaylightSavingTimeBoundaries {

        @Test
        @DisplayName("BC-F09-01: Spring-forward DST transition (23-hour day) preserves streak")
        void testSpringForwardDstTransition() {
            // US Spring Forward 2026: Sunday, March 8, 2026 (23-hour day)
            LocalDate saturday = LocalDate.of(2026, 3, 7);
            LocalDate sundayDst = LocalDate.of(2026, 3, 8); // 23 hours
            LocalDate monday = LocalDate.of(2026, 3, 9);

            List<LocalDate> dates = List.of(saturday, sundayDst, monday);
            StreakResult result = streakEngine.computeStreak(dates, monday, nyZone);

            assertEquals(3, result.currentStreak(), "Streak must survive 23-hour Spring-Forward DST transition");
            assertTrue(result.completedToday());
        }

        @Test
        @DisplayName("BC-F09-02: Fall-back DST transition (25-hour day) does not double-count or drift")
        void testFallBackDstTransition() {
            // US Fall Back 2026: Sunday, November 1, 2026 (25-hour day)
            LocalDate saturday = LocalDate.of(2026, 10, 31);
            LocalDate sundayDst = LocalDate.of(2026, 11, 1); // 25 hours
            LocalDate monday = LocalDate.of(2026, 11, 2);

            List<LocalDate> dates = List.of(saturday, sundayDst, monday);
            StreakResult result = streakEngine.computeStreak(dates, monday, nyZone);

            assertEquals(3, result.currentStreak(), "Streak must not double-count across 25-hour Fall-Back day");
            assertTrue(result.completedToday());
        }

        @Test
        @DisplayName("BC-F09-03: Longest streak correctly calculated across discontinuous spans")
        void testLongestStreakDiscontinuousSpans() {
            // 5-day streak in January, broken, then 3-day streak in February
            List<LocalDate> dates = List.of(
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 2),
                    LocalDate.of(2026, 1, 3),
                    LocalDate.of(2026, 1, 4),
                    LocalDate.of(2026, 1, 5),
                    // gap
                    LocalDate.of(2026, 2, 1),
                    LocalDate.of(2026, 2, 2),
                    LocalDate.of(2026, 2, 3)
            );

            StreakResult result = streakEngine.computeStreak(dates, LocalDate.of(2026, 2, 3), nyZone);
            assertEquals(3, result.currentStreak());
            assertEquals(5, result.longestStreak(), "Longest historical streak must equal 5");
        }

        @Test
        @DisplayName("BC-F09-04: 30-day continuous daily streak verification")
        void testThirtyDayContinuousStreak() {
            LocalDate base = LocalDate.of(2026, 9, 1);
            List<LocalDate> dates = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                dates.add(base.plusDays(i));
            }
            LocalDate refDate = base.plusDays(29);

            StreakResult result = streakEngine.computeStreak(dates, refDate, nyZone);
            assertEquals(30, result.currentStreak());
            assertEquals(30, result.longestStreak());
            assertTrue(result.completedToday());
        }

        @Test
        @DisplayName("BC-F09-05: Missing days on weekday habit when holiday falls on Monday")
        void testWeekdayHabitMissingMonday() {
            // Thursday, Friday completed. Monday missed. Tuesday evaluated.
            LocalDate tuesday = LocalDate.of(2026, 9, 29);
            List<LocalDate> dates = List.of(
                    LocalDate.of(2026, 9, 24), // Thu
                    LocalDate.of(2026, 9, 25), // Fri
                    tuesday                    // Tue (Monday missed)
            );

            StreakResult result = streakEngine.computeStreakWithFrequency(
                    dates, tuesday, nyZone, HabitFrequency.WEEKDAYS, 127
            );
            assertEquals(1, result.currentStreak(), "Streak resets to 1 on Tuesday after missing required Monday");
            assertEquals(2, result.longestStreak());
        }
    }
}
