package com.tracker.rag.e2e.tier1;

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

@DisplayName("Tier 1: Deterministic Academic Grade Engine Tests (F05, F06, F07)")
public class Tier1AcademicGradeEngineTest {

    private GradeCalculationEngine gradeEngine;

    @BeforeEach
    void setUp() {
        gradeEngine = new DeterministicGradeCalculationEngineStub();
    }

    @Nested
    @DisplayName("F05: Assessment Category Weights Contract Tests")
    class CategoryWeightsTests {

        @Test
        @DisplayName("TC-F05-01: Create Category with valid syllabus weight and drop rule")
        void testValidCategoryCreation() {
            CategoryGradeInput category = new CategoryGradeInput("Homework", new BigDecimal("40.00"), 1, List.of(
                    new BigDecimal("90.00"), new BigDecimal("95.00"), new BigDecimal("80.00")
            ));

            assertEquals("Homework", category.categoryName());
            assertEquals(new BigDecimal("40.00"), category.weightPercent());
            assertEquals(1, category.dropLowestN());
            assertEquals(3, category.scores().size());
        }

        @Test
        @DisplayName("TC-F05-02: Category weight cannot be negative")
        void testNegativeWeightRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                    new CategoryGradeInput("Quizzes", new BigDecimal("-10.00"), 0, Collections.emptyList()));
        }

        @Test
        @DisplayName("TC-F05-03: Category dropLowestN cannot be negative")
        void testNegativeDropCountRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                    new CategoryGradeInput("Labs", new BigDecimal("20.00"), -1, Collections.emptyList()));
        }

        @Test
        @DisplayName("TC-F05-04: Null scores list defaults to immutable empty list")
        void testNullScoresDefaultsToEmpty() {
            CategoryGradeInput cat = new CategoryGradeInput("Final Exam", new BigDecimal("30.00"), 0, null);
            assertNotNull(cat.scores());
            assertTrue(cat.scores().isEmpty());
        }

        @Test
        @DisplayName("TC-F05-05: Category weight distribution verification")
        void testSyllabusWeightsSum100() {
            List<CategoryGradeInput> categories = List.of(
                    new CategoryGradeInput("HW", new BigDecimal("30.00"), 1, Collections.emptyList()),
                    new CategoryGradeInput("Midterm", new BigDecimal("30.00"), 0, Collections.emptyList()),
                    new CategoryGradeInput("Final", new BigDecimal("40.00"), 0, Collections.emptyList())
            );

            BigDecimal sum = categories.stream().map(CategoryGradeInput::weightPercent).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(new BigDecimal("100.00"), sum);
        }
    }

    @Nested
    @DisplayName("F06: Deterministic Current Grade Calculation Tests")
    class GradeCalculationTests {

        @Test
        @DisplayName("TC-F06-01: Empty or unassessed categories return unassessed status")
        void testUnassessedCourse() {
            CourseGradeResult result = gradeEngine.calculateCourseGrade(Collections.emptyList());
            assertFalse(result.isAssessed());
            assertNull(result.currentGradePercent());
        }

        @Test
        @DisplayName("TC-F06-02: Weighted average across multiple categories without drops")
        void testSimpleWeightedGrade() {
            // HW (50% weight): 90.00
            // Exams (50% weight): 80.00
            // Expected Grade: (90 * 50 + 80 * 50) / 100 = 85.00
            List<CategoryGradeInput> categories = List.of(
                    new CategoryGradeInput("HW", new BigDecimal("50.00"), 0, List.of(new BigDecimal("90.00"))),
                    new CategoryGradeInput("Exams", new BigDecimal("50.00"), 0, List.of(new BigDecimal("80.00")))
            );

            CourseGradeResult result = gradeEngine.calculateCourseGrade(categories);
            assertTrue(result.isAssessed());
            assertEquals(new BigDecimal("85.00"), result.currentGradePercent());
            assertTrue(result.isPassing());
        }

        @Test
        @DisplayName("TC-F06-03: Drop lowest score policy drops lowest when k > d")
        void testDropLowestWhenItemsExceedDropCount() {
            // HW (100% weight, dropLowestN = 1): scores [60, 90, 100]. Dropped: 60. Retained: 90, 100. Avg = 95.00
            List<CategoryGradeInput> categories = List.of(
                    new CategoryGradeInput("HW", new BigDecimal("100.00"), 1, List.of(
                            new BigDecimal("60.00"), new BigDecimal("90.00"), new BigDecimal("100.00")
                    ))
            );

            CourseGradeResult result = gradeEngine.calculateCourseGrade(categories);
            assertEquals(new BigDecimal("95.00"), result.currentGradePercent());
        }

        @Test
        @DisplayName("TC-F06-04: Drop lowest does not drop scores when k <= d")
        void testDropLowestWhenItemsEqualOrLessThanDropCount() {
            // HW (100% weight, dropLowestN = 1): single score [80.00]. Since k (1) <= d (1), 80 is NOT dropped.
            List<CategoryGradeInput> categories = List.of(
                    new CategoryGradeInput("HW", new BigDecimal("100.00"), 1, List.of(new BigDecimal("80.00")))
            );

            CourseGradeResult result = gradeEngine.calculateCourseGrade(categories);
            assertEquals(new BigDecimal("80.00"), result.currentGradePercent());
        }

        @Test
        @DisplayName("TC-F06-05: Extra credit / bonus scores above 100% compute correctly without overflow")
        void testExtraCreditScore() {
            List<CategoryGradeInput> categories = List.of(
                    new CategoryGradeInput("Projects", new BigDecimal("100.00"), 0, List.of(new BigDecimal("105.00")))
            );

            CourseGradeResult result = gradeEngine.calculateCourseGrade(categories);
            assertEquals(new BigDecimal("105.00"), result.currentGradePercent());
        }
    }

    @Nested
    @DisplayName("F07: Required Final Exam Score Target Math Tests")
    class RequiredFinalScoreTests {

        @Test
        @DisplayName("TC-F07-01: Achievable final exam score for target 90% (Earned 85% on 70% weight, Final 30%)")
        void testAchievableRequiredScore() {
            // Earned: 85.00% on 70% weight => Earned points = 85 * 0.70 = 59.50
            // Target: 90.00%
            // Points needed = 90.00 - 59.50 = 30.50
            // Final weight: 30% => Required score = 30.50 * 100 / 30 = 101.67 -> impossible (>100)
            // Let's test target 88.00%:
            // Points needed = 88.00 - 59.50 = 28.50
            // Required score = 28.50 * 100 / 30 = 95.00%
            List<CategoryGradeInput> completed = List.of(
                    new CategoryGradeInput("Midterms", new BigDecimal("70.00"), 0, List.of(new BigDecimal("85.00")))
            );

            FinalExamRequirement req = gradeEngine.calculateRequiredFinalScore(
                    completed, new BigDecimal("30.00"), new BigDecimal("88.00")
            );

            assertTrue(req.isAchievable());
            assertEquals(new BigDecimal("95.00"), req.requiredScore());
            assertEquals(new BigDecimal("59.50"), req.earnedPoints());
        }

        @Test
        @DisplayName("TC-F07-02: Impossible target score requiring > 100% on final exam")
        void testImpossibleTargetScore() {
            // Earned: 70.00% on 70% weight => Earned points = 49.00
            // Target: 90.00% => Needs 41.00 points on 30% weight => Required score = 136.67%
            List<CategoryGradeInput> completed = List.of(
                    new CategoryGradeInput("Coursework", new BigDecimal("70.00"), 0, List.of(new BigDecimal("70.00")))
            );

            FinalExamRequirement req = gradeEngine.calculateRequiredFinalScore(
                    completed, new BigDecimal("30.00"), new BigDecimal("90.00")
            );

            assertFalse(req.isAchievable(), "Score exceeding 100% must be marked unachievable");
            assertEquals(new BigDecimal("136.67"), req.requiredScore());
        }

        @Test
        @DisplayName("TC-F07-03: Target already guaranteed even with 0.00% on final")
        void testTargetAlreadyGuaranteed() {
            // Earned: 95.00% on 80% weight => Earned points = 76.00
            // Target: 70.00% (passing grade C)
            // Points needed = 70.00 - 76.00 = -6.00 (already exceeded)
            List<CategoryGradeInput> completed = List.of(
                    new CategoryGradeInput("Midterms", new BigDecimal("80.00"), 0, List.of(new BigDecimal("95.00")))
            );

            FinalExamRequirement req = gradeEngine.calculateRequiredFinalScore(
                    completed, new BigDecimal("20.00"), new BigDecimal("70.00")
            );

            assertTrue(req.isAchievable());
            assertTrue(req.isGuaranteed());
            assertEquals(new BigDecimal("0.00"), req.requiredScore());
        }

        @Test
        @DisplayName("TC-F07-04: Target calculation with zero remaining weight")
        void testZeroRemainingWeight() {
            // Course fully completed: 100% weight already assessed, earned 92.00
            List<CategoryGradeInput> completed = List.of(
                    new CategoryGradeInput("All", new BigDecimal("100.00"), 0, List.of(new BigDecimal("92.00")))
            );

            FinalExamRequirement achieved = gradeEngine.calculateRequiredFinalScore(
                    completed, BigDecimal.ZERO, new BigDecimal("90.00")
            );
            assertTrue(achieved.isAchievable());
            assertEquals(new BigDecimal("0.00"), achieved.requiredScore());

            FinalExamRequirement impossible = gradeEngine.calculateRequiredFinalScore(
                    completed, BigDecimal.ZERO, new BigDecimal("95.00")
            );
            assertFalse(impossible.isAchievable());
            assertNull(impossible.requiredScore());
        }

        @Test
        @DisplayName("TC-F07-05: Required score with multi-category drop-lowest syllabus")
        void testRequiredScoreWithDroppedAssessments() {
            // HW (40% weight, drop 1 lowest): scores [50, 90, 90]. 50 dropped. Avg = 90.00. Points = 36.00
            // Midterm (30% weight): score [80]. Avg = 80.00. Points = 24.00
            // Total earned points = 36.00 + 24.00 = 60.00
            // Target = 85.00. Points needed = 25.00
            // Final weight: 30% => Required score = 25.00 * 100 / 30 = 83.33%
            List<CategoryGradeInput> completed = List.of(
                    new CategoryGradeInput("HW", new BigDecimal("40.00"), 1, List.of(
                            new BigDecimal("50.00"), new BigDecimal("90.00"), new BigDecimal("90.00")
                    )),
                    new CategoryGradeInput("Midterm", new BigDecimal("30.00"), 0, List.of(new BigDecimal("80.00")))
            );

            FinalExamRequirement req = gradeEngine.calculateRequiredFinalScore(
                    completed, new BigDecimal("30.00"), new BigDecimal("85.00")
            );

            assertTrue(req.isAchievable());
            assertEquals(new BigDecimal("83.33"), req.requiredScore());
            assertEquals(new BigDecimal("60.00"), req.earnedPoints());
        }
    }
}
