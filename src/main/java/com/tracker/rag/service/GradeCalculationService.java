package com.tracker.rag.service;

import com.tracker.rag.dto.CategoryGradeInput;
import com.tracker.rag.dto.CourseGradeResult;
import com.tracker.rag.dto.FinalExamRequirement;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Deterministic grade calculation service implementing exact BigDecimal math.
 * Computes running weighted course grades and required scores on final assessments.
 */
@Service
public class GradeCalculationService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    private static final int CALC_SCALE = 6;
    private static final int DISPLAY_SCALE = 2;

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
                continue;
            }

            int k = scores.size();
            int d = category.dropLowestN();
            List<BigDecimal> retainedScores;

            if (d > 0 && k > d) {
                List<BigDecimal> sorted = new ArrayList<>(scores);
                Collections.sort(sorted);
                retainedScores = sorted.subList(d, k);
            } else {
                retainedScores = scores;
            }

            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal score : retainedScores) {
                sum = sum.add(score);
            }
            BigDecimal count = BigDecimal.valueOf(retainedScores.size());
            BigDecimal categoryAvg = sum.divide(count, CALC_SCALE, RoundingMode.HALF_UP);
            categoryAverages.put(category.categoryName(), categoryAvg.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP));

            BigDecimal weight = category.weightPercent();
            totalAssessedWeight = totalAssessedWeight.add(weight);
            weightedSum = weightedSum.add(categoryAvg.multiply(weight));
        }

        if (totalAssessedWeight.compareTo(BigDecimal.ZERO) <= 0) {
            return CourseGradeResult.unassessed();
        }

        BigDecimal currentGrade = weightedSum.divide(totalAssessedWeight, DISPLAY_SCALE, RoundingMode.HALF_UP);
        boolean isPassing = currentGrade.compareTo(new BigDecimal("60.00")) >= 0;

        return new CourseGradeResult(currentGrade, categoryAverages, isPassing, true);
    }

    public FinalExamRequirement calculateRequiredFinalScore(
            List<CategoryGradeInput> completedCategories,
            BigDecimal finalExamWeight,
            BigDecimal targetGradePercent
    ) {
        Objects.requireNonNull(targetGradePercent, "targetGradePercent cannot be null");
        Objects.requireNonNull(finalExamWeight, "finalExamWeight cannot be null");

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
                BigDecimal points = avg.multiply(category.weightPercent()).divide(HUNDRED, CALC_SCALE, RoundingMode.HALF_UP);
                earnedPoints = earnedPoints.add(points);
            }
        }

        BigDecimal remainingWeight = finalExamWeight;

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
