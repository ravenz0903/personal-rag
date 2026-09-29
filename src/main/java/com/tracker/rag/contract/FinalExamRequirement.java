package com.tracker.rag.contract;

import java.math.BigDecimal;

public record FinalExamRequirement(
        BigDecimal requiredScore,
        boolean isAchievable,
        BigDecimal remainingWeight,
        BigDecimal earnedPoints,
        String reason
) {
    public boolean isGuaranteed() {
        return isAchievable && requiredScore != null && requiredScore.compareTo(BigDecimal.ZERO) <= 0;
    }
}

