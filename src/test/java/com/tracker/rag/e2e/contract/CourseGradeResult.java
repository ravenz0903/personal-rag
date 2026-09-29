package com.tracker.rag.e2e.contract;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

public record CourseGradeResult(
        BigDecimal currentGradePercent,
        Map<String, BigDecimal> categoryAverages,
        boolean isPassing,
        boolean isAssessed
) {
    public CourseGradeResult {
        if (categoryAverages == null) {
            categoryAverages = Collections.emptyMap();
        } else {
            categoryAverages = Map.copyOf(categoryAverages);
        }
    }

    public static CourseGradeResult unassessed() {
        return new CourseGradeResult(null, Collections.emptyMap(), false, false);
    }
}
