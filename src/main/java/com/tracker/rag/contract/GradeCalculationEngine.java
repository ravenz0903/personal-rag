package com.tracker.rag.contract;

import java.math.BigDecimal;
import java.util.List;

public interface GradeCalculationEngine {
    CourseGradeResult calculateCourseGrade(List<CategoryGradeInput> categories);

    FinalExamRequirement calculateRequiredFinalScore(
            List<CategoryGradeInput> completedCategories,
            BigDecimal finalExamWeight,
            BigDecimal targetGradePercent
    );
}

