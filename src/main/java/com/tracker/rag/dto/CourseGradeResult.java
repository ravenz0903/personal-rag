package com.tracker.rag.dto;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

public record CourseGradeResult(
        BigDecimal currentGrade,
        Map<String, BigDecimal> categoryAverages,
        boolean passing,
        boolean assessed
) {
    public static CourseGradeResult unassessed() {
        return new CourseGradeResult(null, Collections.emptyMap(), false, false);
    }
}
