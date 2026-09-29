package com.tracker.rag.e2e.tier2;

import com.tracker.rag.e2e.contract.*;
import com.tracker.rag.e2e.stub.DeterministicGradeCalculationEngineStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tier 2: Boundary & Corner Cases - Academic Grade Math Engine (F05-F07)")
public class Tier2AcademicGradeBoundariesTest {

    private GradeCalculationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DeterministicGradeCalculationEngineStub();
    }

    @Nested
    @DisplayName("Syllabus Category Weights Boundary Tests")
    class CategoryWeightsBoundaries {

        @Test
        @DisplayName("BC-F05-01: Category with 0.00% weight is valid (e.g. ungraded practice)")
        void testZeroWeightCategory() {
            CategoryGradeInput cat = new CategoryGradeInput("Ungraded Practice", BigDecimal.ZERO, 0, List.of(new BigDecimal("100.00")));
            assertEquals(BigDecimal.ZERO, cat.weightPercent());
        }

        @Test
        @DisplayName("BC-F05-02: Category with 100.00% weight (single category syllabus)")
        void testHundredPercentWeightCategory() {
            CategoryGradeInput cat = new CategoryGradeInput("Comprehensive Final", new BigDecimal("100.00"), 0, List.of(new BigDecimal("88.00")));
            CourseGradeResult result = engine.calculateCourseGrade(List.of(cat));
            assertEquals(new BigDecimal("88.00"), result.currentGradePercent());
        }

        @Test
        @DisplayName("BC-F05-03: Drop count greater than total submitted items (d > k)")
        void testDropCountExceedsItemCount() {
            // Drop 3 lowest, but only 2 items submitted: [80, 90]. Since k <= d, none are dropped!
            CategoryGradeInput cat = new CategoryGradeInput("Labs", new BigDecimal("100.00"), 3, List.of(
                    new BigDecimal("80.00"), new BigDecimal("90.00")
            ));
            CourseGradeResult result = engine.calculateCourseGrade(List.of(cat));
            // Average of 80 and 90 = 85.00
            assertEquals(new BigDecimal("85.00"), result.currentGradePercent());
        }

        @Test
        @DisplayName("BC-F05-04: Drop count exactly equal to total items (d = k)")
        void testDropCountEqualsItemCount() {
            // Drop 2 lowest, exactly 2 items submitted: [70, 70]. Since k <= d, none are dropped!
            CategoryGradeInput cat = new CategoryGradeInput("Quizzes", new BigDecimal("100.00"), 2, List.of(
                    new BigDecimal("70.00"), new BigDecimal("70.00")
            ));
            CourseGradeResult result = engine.calculateCourseGrade(List.of(cat));
            assertEquals(new BigDecimal("70.00"), result.currentGradePercent());
        }

        @Test
        @DisplayName("BC-F05-05: Category with negative weight throws IllegalArgumentException")
        void testNegativeWeightCategoryThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                    new CategoryGradeInput("Homework", new BigDecimal("-0.01"), 0, Collections.emptyList()));
        }
    }

    @Nested
    @DisplayName("Deterministic Grade Calculation Boundary Tests")
    class GradeCalculationBoundaries {

        @Test
        @DisplayName("BC-F06-01: Zero scores on all submitted items calculates 0.00%")
        void testAllZeroScores() {
            CategoryGradeInput cat = new CategoryGradeInput("HW", new BigDecimal("100.00"), 0, List.of(
                    new BigDecimal("0.00"), new BigDecimal("0.00")
            ));
            CourseGradeResult result = engine.calculateCourseGrade(List.of(cat));
            assertEquals(new BigDecimal("0.00"), result.currentGradePercent());
            assertFalse(result.isPassing());
        }

        @Test
        @DisplayName("BC-F06-02: Partial syllabus with total weight 40% normalizes current grade")
        void testPartialSyllabusNormalization() {
            // Only HW (40% weight) graded so far with 100%. Remaining 60% not yet graded.
            // Current grade normalized by graded weight = 100.00%
            CategoryGradeInput hw = new CategoryGradeInput("HW", new BigDecimal("40.00"), 0, List.of(new BigDecimal("100.00")));
            CategoryGradeInput exams = new CategoryGradeInput("Exams", new BigDecimal("60.00"), 0, Collections.emptyList());

            CourseGradeResult result = engine.calculateCourseGrade(List.of(hw, exams));
            assertEquals(new BigDecimal("100.00"), result.currentGradePercent());
        }

        @Test
        @DisplayName("BC-F06-03: Extra credit score of 120.00% computes correctly")
        void testHighExtraCreditScore() {
            CategoryGradeInput cat = new CategoryGradeInput("Bonus", new BigDecimal("100.00"), 0, List.of(new BigDecimal("120.00")));
            CourseGradeResult result = engine.calculateCourseGrade(List.of(cat));
            assertEquals(new BigDecimal("120.00"), result.currentGradePercent());
        }

        @Test
        @DisplayName("BC-F06-04: Rounding mode HALF_UP precision verification")
        void testRoundingModeHalfUp() {
            // Scores [100, 100, 99]: avg = 299/3 = 99.6666... Rounded to 2 decimals = 99.67
            CategoryGradeInput cat = new CategoryGradeInput("HW", new BigDecimal("100.00"), 0, List.of(
                    new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("99.00")
            ));
            CourseGradeResult result = engine.calculateCourseGrade(List.of(cat));
            assertEquals(new BigDecimal("99.67"), result.currentGradePercent());
        }

        @Test
        @DisplayName("BC-F06-05: Passing boundary test: 59.99 is failing, 60.00 is passing")
        void testPassingThresholdBoundaries() {
            CategoryGradeInput failing = new CategoryGradeInput("E", new BigDecimal("100.00"), 0, List.of(new BigDecimal("59.99")));
            assertFalse(engine.calculateCourseGrade(List.of(failing)).isPassing());

            CategoryGradeInput passing = new CategoryGradeInput("E", new BigDecimal("100.00"), 0, List.of(new BigDecimal("60.00")));
            assertTrue(engine.calculateCourseGrade(List.of(passing)).isPassing());
        }
    }

    @Nested
    @DisplayName("Required Final Score Target Math Boundary Tests")
    class RequiredFinalScoreBoundaries {

        @Test
        @DisplayName("BC-F07-01: Required final score when target is mathematically impossible (> 100%)")
        void testImpossibleTargetScoreBoundary() {
            // Earned 60% on 60% weight => Earned points = 36.00. Target = 90.00.
            // Points needed = 54.00. Remaining weight = 40% => Required score = 54 * 100 / 40 = 135.00%
            List<CategoryGradeInput> completed = List.of(
                    new CategoryGradeInput("Midterm", new BigDecimal("60.00"), 0, List.of(new BigDecimal("60.00")))
            );
            FinalExamRequirement req = engine.calculateRequiredFinalScore(
                    completed, new BigDecimal("40.00"), new BigDecimal("90.00")
            );
            assertFalse(req.isAchievable());
            assertEquals(new BigDecimal("135.00"), req.requiredScore());
        }

        @Test
        @DisplayName("BC-F07-02: Target already achieved; required score clamped to 0.00%")
        void testTargetAlreadyExceededBoundary() {
            // Earned 100% on 80% weight => Earned points = 80.00. Target = 75.00.
            List<CategoryGradeInput> completed = List.of(
                    new CategoryGradeInput("Work", new BigDecimal("80.00"), 0, List.of(new BigDecimal("100.00")))
            );
            FinalExamRequirement req = engine.calculateRequiredFinalScore(
                    completed, new BigDecimal("20.00"), new BigDecimal("75.00")
            );
            assertTrue(req.isAchievable());
            assertTrue(req.isGuaranteed());
            assertEquals(new BigDecimal("0.00"), req.requiredScore());
        }

        @Test
        @DisplayName("BC-F07-03: Exactly 100.00% required on final exam boundary")
        void testExactly100RequiredBoundary() {
            // Earned 80% on 50% weight => Earned points = 40.00. Target = 90.00.
            // Points needed = 50.00. Remaining weight = 50% => Required score = 50 * 100 / 50 = 100.00%
            List<CategoryGradeInput> completed = List.of(
                    new CategoryGradeInput("Work", new BigDecimal("50.00"), 0, List.of(new BigDecimal("80.00")))
            );
            FinalExamRequirement req = engine.calculateRequiredFinalScore(
                    completed, new BigDecimal("50.00"), new BigDecimal("90.00")
            );
            assertTrue(req.isAchievable());
            assertEquals(new BigDecimal("100.00"), req.requiredScore());
        }

        @Test
        @DisplayName("BC-F07-04: Remaining weight is 0.00% and target is not met")
        void testZeroRemainingWeightUnmetBoundary() {
            List<CategoryGradeInput> completed = List.of(
                    new CategoryGradeInput("All", new BigDecimal("100.00"), 0, List.of(new BigDecimal("85.00")))
            );
            FinalExamRequirement req = engine.calculateRequiredFinalScore(
                    completed, BigDecimal.ZERO, new BigDecimal("90.00")
            );
            assertFalse(req.isAchievable());
            assertNull(req.requiredScore());
        }

        @Test
        @DisplayName("BC-F07-05: Null arguments to calculateRequiredFinalScore throw NullPointerException")
        void testNullArgumentsThrow() {
            assertThrows(NullPointerException.class, () ->
                    engine.calculateRequiredFinalScore(Collections.emptyList(), null, new BigDecimal("90.00")));
            assertThrows(NullPointerException.class, () ->
                    engine.calculateRequiredFinalScore(Collections.emptyList(), new BigDecimal("30.00"), null));
        }
    }
}
