package com.tracker.rag.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record CategoryGradeInput(
        String categoryName,
        BigDecimal weightPercent,
        int dropLowestN,
        List<BigDecimal> scores
) {}
