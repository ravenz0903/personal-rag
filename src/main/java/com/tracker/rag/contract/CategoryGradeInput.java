package com.tracker.rag.contract;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record CategoryGradeInput(
        String categoryName,
        BigDecimal weightPercent, // e.g. 30.00
        int dropLowestN,
        List<BigDecimal> scores // each 0.00 to 100.00 (or >100 for extra credit)
) {
    public CategoryGradeInput {
        Objects.requireNonNull(categoryName, "categoryName cannot be null");
        Objects.requireNonNull(weightPercent, "weightPercent cannot be null");
        if (weightPercent.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Category weight cannot be negative: " + weightPercent);
        }
        if (dropLowestN < 0) {
            throw new IllegalArgumentException("dropLowestN cannot be negative: " + dropLowestN);
        }
        if (scores == null) {
            scores = Collections.emptyList();
        } else {
            scores = List.copyOf(scores);
        }
    }
}

