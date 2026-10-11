package com.tracker.rag.service.desktop;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalActionTimesTest {
    @Test void resolvesLeapTomorrowAndRejectsInventedDatesTimes(){
        var context=LocalActionTimes.resolve("Remind me tomorrow at 9:05 pm",ZoneId.of("Asia/Kolkata"),Instant.parse("2032-02-28T06:00:00Z"));
        assertEquals(Instant.parse("2032-02-29T15:35:00Z"),context.instant("2032-02-29T21:05"));
        assertThrows(IllegalArgumentException.class,()->context.instant("2032-03-01T21:05"));
        assertThrows(IllegalArgumentException.class,()->context.instant("2032-02-29T15:00"));
        var vague=LocalActionTimes.resolve("Tomorrow afternoon",ZoneId.of("UTC"),Instant.parse("2032-02-28T06:00:00Z"));assertTrue(vague.clocks().isEmpty());
    }
    @Test void validatesOvernightAndRejectsDstGapsAndRepeatedClockTimes(){
        var night=LocalActionTimes.resolve("2030-05-04 23:45 to 2030-05-05 00:15",ZoneId.of("UTC"),Instant.EPOCH);
        assertTrue(night.instant("2030-05-05T00:15").isAfter(night.instant("2030-05-04T23:45")));
        var gap=LocalActionTimes.resolve("2026-03-08 at 02:30",ZoneId.of("America/New_York"),Instant.EPOCH);
        assertThrows(IllegalArgumentException.class,()->gap.instant("2026-03-08T02:30"));
        var overlap=LocalActionTimes.resolve("2026-11-01 at 01:30",ZoneId.of("America/New_York"),Instant.EPOCH);
        assertThrows(IllegalArgumentException.class,()->overlap.instant("2026-11-01T01:30"));
    }
    @Test void invalidDatesAndClockRangesFailBeforeInference(){
        assertThrows(DateTimeException.class,()->LocalActionTimes.resolve("2030-04-31 at 10:15",ZoneId.of("UTC"),Instant.EPOCH));
        assertThrows(DateTimeException.class,()->LocalActionTimes.resolve("2030-04-30 at 25:15",ZoneId.of("UTC"),Instant.EPOCH));
    }
}
