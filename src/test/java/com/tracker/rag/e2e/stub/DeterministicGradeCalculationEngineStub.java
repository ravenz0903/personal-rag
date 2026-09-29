package com.tracker.rag.e2e.stub;

import com.tracker.rag.e2e.contract.CategoryGradeInput;
import com.tracker.rag.e2e.contract.CourseGradeResult;
import com.tracker.rag.e2e.contract.FinalExamRequirement;
import com.tracker.rag.e2e.contract.GradeCalculationEngine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Deterministic grade calculation engine implementing exact BigDecimal math
 * per spec_report.md § 5.1 and PROJECT.md F06 & F07.
 */
public class DeterministicGradeCalculationEngineStub implements GradeCalculationEngine {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    private static final int CALC_SCALE = 6;
    private static final int DISPLAY_SCALE = 2;

    @Override
    public CourseGradeResult calculateCourseGrade(List<CategoryGradeInput> categories) {
        if (categories == null || categories.isEmpty()) {
            return CourseGradeResult.unassessed();
        }

        Map<String, BigDecimal> categoryAverages = new LinkedHashMap<>();
        BigDecimal totalAssessedWeight = BigDecimal.ZERO;
        BigDecimal weightedSum = BigDecimal.ZERO;

        for (CategoryGradeInput category : categories) {
            List<BigDecimal> scores = category.scores();
            if (scores.isEmpty()) {
                continue; // Unassessed category
            }

            int k = scores.size();
            int d = category.dropLowestN();
            List<BigDecimal> retainedScores;

            if (d > 0 && k > d) {
                // Sort ascending to drop lowest d scores
                List<BigDecimal> sorted = new ArrayList<>(scores);
                Collections.sort(sorted);
                retainedScores = sorted.subList(d, k);
            } else {
                // If k <= d, no scores are dropped
                retainedScores = scores;
            }

            // Calculate category average
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal score : retainedScores) {
                sum = sum.add(score);
            }
            BigDecimal count = BigDecimal.valueOf(retainedScores.size());
            BigDecimal categoryAvg = sum.divide(count, CALC_SCALE, RoundingMode.HALF_UP);
            categoryAverages.put(category.categoryName(), categoryAvg.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP));

            // Accumulate weighted points
            BigDecimal weight = category.weightPercent();
            totalAssessedWeight = totalAssessedWeight.add(weight);
            weightedSum = weightedSum.add(categoryAvg.multiply(weight));
        }

        if (totalAssessedWeight.compareTo(BigDecimal.ZERO) <= 0) {
            return CourseGradeResult.unassessed();
        }

        // Current grade = (sum(categoryAvg * weight)) / totalAssessedWeight
        BigDecimal currentGrade = weightedSum.divide(totalAssessedWeight, DISPLAY_SCALE, RoundingMode.HALF_UP);
        boolean isPassing = currentGrade.compareTo(new BigDecimal("60.00")) >= 0;

        return new CourseGradeResult(currentGrade, categoryAverages, isPassing, true);
    }

    @Override
    public FinalExamRequirement calculateRequiredFinalScore(
            List<CategoryGradeInput> completedCategories,
            BigDecimal finalExamWeight,
            BigDecimal targetGradePercent
    ) {
        Objects.requireNonNull(targetGradePercent, "targetGradePercent cannot be null");
        Objects.requireNonNull(finalExamWeight, "finalExamWeight cannot be null");

        // 1. Calculate earned weighted points from completed categories
        BigDecimal earnedPoints = BigDecimal.ZERO;
        if (completedCategories != null) {
            for (CategoryGradeInput category : completedCategories) {
                if (category.scores().isEmpty()) continue;
                List<BigDecimal> scores = category.scores();
                int k = scores.size();
                int d = category.dropLowestN();
                List<BigDecimal> retained = (d > 0 && k > d)
                        ? new ArrayList<>(scores).stream().sorted().skip(d).toList()
                        : scores;

                BigDecimal sum = BigDecimal.ZERO;
                for (BigDecimal s : retained) sum = sum.add(s);
                BigDecimal avg = sum.divide(BigDecimal.valueOf(retained.size()), CALC_SCALE, RoundingMode.HALF_UP);
                // Points = avg * (weight / 100)
                BigDecimal points = avg.multiply(category.weightPercent()).divide(HUNDRED, CALC_SCALE, RoundingMode.HALF_UP);
                earnedPoints = earnedPoints.add(points);
            }
        }

        BigDecimal remainingWeight = finalExamWeight;

        // If remaining weight is 0
        if (remainingWeight.compareTo(BigDecimal.ZERO) <= 0) {
            if (earnedPoints.compareTo(targetGradePercent) >= 0) {
                return new FinalExamRequirement(BigDecimal.ZERO.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP),
                        true, remainingWeight, earnedPoints.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP),
                        "Target already achieved; no remaining assessments.");
            } else {
                return new FinalExamRequirement(null, false, remainingWeight,
                        earnedPoints.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP),
                        "No remaining weight to achieve target grade.");
            }
        }

        // S_required = (T - P_earned) * 100 / W_remaining
        BigDecimal pointsNeeded = targetGradePercent.subtract(earnedPoints);
        BigDecimal requiredScore = pointsNeeded.multiply(HUNDRED)
                .divide(remainingWeight, DISPLAY_SCALE, RoundingMode.HALF_UP);

        if (requiredScore.compareTo(BigDecimal.ZERO) <= 0) {
            return new FinalExamRequirement(BigDecimal.ZERO.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP),
                    true, remainingWeight, earnedPoints.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP),
                    "Target already guaranteed even with 0.00% on final exam.");
        } else if (requiredScore.compareTo(HUNDRED) <= 0) {
            return new FinalExamRequirement(requiredScore, true, remainingWeight,
                    earnedPoints.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP),
                    "Achievable target score.");
        } else {
            return new FinalExamRequirement(requiredScore, false, remainingWeight,
                    earnedPoints.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP),
                    "Mathematically impossible without extra credit (exceeds 100%).");
        }
    }
}
