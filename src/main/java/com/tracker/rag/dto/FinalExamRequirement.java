package com.tracker.rag.dto;

import java.math.BigDecimal;

public record FinalExamRequirement(
        BigDecimal requiredScore,
        boolean achievable,
        BigDecimal remainingWeight,
        BigDecimal earnedPoints,
        String statusMessage
) {}
