package com.tracker.rag.e2e.contract;

import java.time.DayOfWeek;
import java.time.LocalDate;

public enum HabitFrequency {
    DAILY,
    WEEKDAYS,
    WEEKENDS,
    CUSTOM_DAYS;

    public boolean isRequiredDay(LocalDate date, int customDaysMask) {
        DayOfWeek dow = date.getDayOfWeek();
        return switch (this) {
            case DAILY -> true;
            case WEEKDAYS -> dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
            case WEEKENDS -> dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            case CUSTOM_DAYS -> {
                // Bitmask: bit 0 = Monday, bit 6 = Sunday
                int bitIndex = dow.getValue() - 1;
                yield ((customDaysMask >> bitIndex) & 1) == 1;
            }
        };
    }
}
