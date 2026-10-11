package com.tracker.rag.service.desktop;

import java.time.*;
import java.time.format.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.*;

/** Small trusted date/time vocabulary; unsupported expressions require clarification. */
public record LocalActionTimes(Set<LocalDate> dates, Set<LocalTime> clocks, ZoneId zone) {
    public static LocalActionTimes resolve(String text, ZoneId zone, Instant now) {
        Set<LocalDate> dates = new LinkedHashSet<>(); Set<LocalTime> clocks = new LinkedHashSet<>();
        String input = text.toLowerCase(Locale.ROOT); LocalDate today = now.atZone(zone).toLocalDate();
        Matcher days = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b").matcher(input);
        while (days.find()) dates.add(LocalDate.parse(days.group())); // Invalid dates fail before inference.
        if (Pattern.compile("\\btoday\\b").matcher(input).find()) dates.add(today);
        if (Pattern.compile("\\btomorrow\\b").matcher(input).find()) dates.add(today.plusDays(1));
        for (DayOfWeek day : DayOfWeek.values()) if (Pattern.compile("\\b" + day.name().toLowerCase(Locale.ROOT) + "\\b").matcher(input).find())
            dates.add(today.with(TemporalAdjusters.nextOrSame(day)));
        Matcher time = Pattern.compile("(?<![\\d:])(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?(?![\\w:])").matcher(input);
        while (time.find()) {
            if (time.group(2) == null && time.group(3) == null) continue; // Numbers/titles are not clock times.
            int hour = Integer.parseInt(time.group(1)), minute = time.group(2) == null ? 0 : Integer.parseInt(time.group(2));
            if (time.group(3) != null) { if (hour < 1 || hour > 12) throw new IllegalArgumentException("Invalid clock time"); hour = hour % 12 + (time.group(3).equals("pm") ? 12 : 0); }
            clocks.add(LocalTime.of(hour,minute));
        }
        return new LocalActionTimes(Set.copyOf(dates),Set.copyOf(clocks),zone);
    }
    public Instant instant(String local) {
        if (!local.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")) throw new IllegalArgumentException("Use an exact local date and time");
        LocalDateTime date = LocalDateTime.parse(local, DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT));
        if (!dates.contains(date.toLocalDate()) || !clocks.contains(date.toLocalTime()))
            throw new IllegalArgumentException("The proposal invented a date or time. Specify it explicitly.");
        var offsets = zone.getRules().getValidOffsets(date);
        if (offsets.size() != 1) throw new IllegalArgumentException("This time is skipped or repeated by daylight saving. Choose another time.");
        return date.toInstant(offsets.getFirst());
    }
}
