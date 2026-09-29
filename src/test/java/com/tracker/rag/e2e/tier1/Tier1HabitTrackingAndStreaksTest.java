package com.tracker.rag.e2e.tier1;

import com.tracker.rag.e2e.contract.*;
import com.tracker.rag.e2e.stub.CalendarDayHabitStreakEngineStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tier 1: Habit Tracking and Timezone-Resilient Streak Tests (F08, F09)")
public class Tier1HabitTrackingAndStreaksTest {

    private HabitStreakEngine streakEngine;

    @BeforeEach
    void setUp() {
        streakEngine = new CalendarDayHabitStreakEngineStub();
    }

    @Nested
    @DisplayName("F08: Habit Definition & Daily Logging Tests")
    class HabitDefinitionTests {

        @Test
        @DisplayName("TC-F08-01: Create valid Habit with daily frequency and IANA timezone")
        void testCreateValidHabit() {
            HabitRecord habit = HabitRecord.create("Morning Run", HabitFrequency.DAILY, 1, "America/New_York");

            assertNotNull(habit.id());
            assertEquals(DomainScope.PERSONAL, habit.domain());
            assertEquals("Morning Run", habit.name());
            assertEquals(HabitFrequency.DAILY, habit.frequency());
            assertEquals(1, habit.targetCountPerDay());
            assertEquals("America/New_York", habit.timezone());
            assertFalse(habit.archived());
        }

        @Test
        @DisplayName("TC-F08-02: Habit rejects invalid IANA timezone string")
        void testInvalidTimezoneThrows() {
            assertThrows(ZoneRulesException.class, () ->
                    HabitRecord.create("Hydration", HabitFrequency.DAILY, 1, "Invalid/Timezone"));
        }

        @Test
        @DisplayName("TC-F08-03: Habit target count must be at least 1")
        void testZeroTargetCountThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                    new HabitRecord(null, DomainScope.PERSONAL, "Reading", HabitFrequency.DAILY, 0, 127, "UTC", false));
        }

        @Test
        @DisplayName("TC-F08-04: Habit log records units completed and calendar date")
        void testHabitLogCreation() {
            HabitRecord habit = HabitRecord.create("Study", HabitFrequency.DAILY, 2, "Europe/London");
            LocalDate today = LocalDate.of(2026, 9, 28);
            HabitLogRecord log = HabitLogRecord.create(habit.id(), today, 2);

            assertEquals(habit.id(), log.habitId());
            assertEquals(today, log.logDate());
            assertEquals(2, log.unitsCompleted());
            assertNotNull(log.loggedAt());
        }

        @Test
        @DisplayName("TC-F08-05: HabitLog rejects 0 or negative units completed")
        void testHabitLogZeroUnitsThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                    HabitLogRecord.create(java.util.UUID.randomUUID(), LocalDate.now(), 0));
        }
    }

    @Nested
    @DisplayName("F09: Timezone & DST-Resilient Streaks Tests")
    class HabitStreakEngineTests {

        private final ZoneId nyZone = ZoneId.of("America/New_York");

        @Test
        @DisplayName("TC-F09-01: Zero completions returns zero streak")
        void testZeroCompletionsStreak() {
            StreakResult result = streakEngine.computeStreak(
                    Collections.emptyList(), LocalDate.of(2026, 9, 28), nyZone
            );
            assertEquals(0, result.currentStreak());
            assertEquals(0, result.longestStreak());
            assertFalse(result.completedToday());
            assertNull(result.lastCompletedDate());
        }

        @Test
        @DisplayName("TC-F09-02: Active streak completed today accumulates consecutive calendar days")
        void testActiveStreakCompletedToday() {
            LocalDate today = LocalDate.of(2026, 9, 28);
            List<LocalDate> dates = List.of(
                    today.minusDays(2),
                    today.minusDays(1),
                    today
            );

            StreakResult result = streakEngine.computeStreak(dates, today, nyZone);
            assertEquals(3, result.currentStreak());
            assertEquals(3, result.longestStreak());
            assertTrue(result.completedToday());
            assertTrue(result.isActiveToday());
            assertEquals(today, result.lastCompletedDate());
        }

        @Test
        @DisplayName("TC-F09-03: Grace period when completed yesterday but not yet today")
        void testGracePeriodYesterdayCompleted() {
            LocalDate today = LocalDate.of(2026, 9, 28);
            List<LocalDate> dates = List.of(
                    today.minusDays(2),
                    today.minusDays(1) // Completed yesterday, today pending
            );

            StreakResult result = streakEngine.computeStreak(dates, today, nyZone);
            assertEquals(2, result.currentStreak(), "Grace period preserves yesterday streak count");
            assertFalse(result.completedToday());
            assertTrue(result.isActiveToday(), "Streak is considered active/pending during grace period");
        }

        @Test
        @DisplayName("TC-F09-04: Streak broken when yesterday was missed")
        void testStreakBrokenWhenYesterdayMissed() {
            LocalDate today = LocalDate.of(2026, 9, 28);
            List<LocalDate> dates = List.of(
                    today.minusDays(4),
                    today.minusDays(3),
                    today.minusDays(2) // Missed yesterday and today
            );

            StreakResult result = streakEngine.computeStreak(dates, today, nyZone);
            assertEquals(0, result.currentStreak(), "Current streak must reset to 0 when yesterday was missed");
            assertEquals(3, result.longestStreak(), "Longest historical streak is preserved");
            assertFalse(result.completedToday());
        }

        @Test
        @DisplayName("TC-F09-05: Weekday habit streak skips weekends without breaking")
        void testWeekdayHabitStreakSkipsWeekend() {
            // Monday = 2026-09-28
            // Friday = 2026-09-25
            // Thursday = 2026-09-24
            LocalDate monday = LocalDate.of(2026, 9, 28);
            List<LocalDate> dates = List.of(
                    LocalDate.of(2026, 9, 24), // Thursday
                    LocalDate.of(2026, 9, 25), // Friday
                    monday                     // Monday
            );

            StreakResult result = streakEngine.computeStreakWithFrequency(
                    dates, monday, nyZone, HabitFrequency.WEEKDAYS, 127
            );

            assertEquals(3, result.currentStreak(), "Weekday habit preserves streak across weekend");
            assertTrue(result.completedToday());
        }
    }
}
