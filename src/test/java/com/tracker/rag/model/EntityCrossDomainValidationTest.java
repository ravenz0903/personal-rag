package com.tracker.rag.model;

import com.tracker.rag.domain.CrossDomainReferenceException;
import com.tracker.rag.domain.DomainScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityCrossDomainValidationTest {

    @Test
    @DisplayName("Task: subtask domain must match parent task domain")
    void testSubtaskDomainMustMatchParent() {
        Task parent = new Task(DomainScope.ACADEMICS, "Parent Task", TaskPriority.HIGH, Instant.now());
        Task subtask = new Task(DomainScope.PERSONAL, "Subtask", TaskPriority.MEDIUM, Instant.now());
        subtask.setParentTask(parent);

        assertThatThrownBy(subtask::validateCrossDomainReferences)
                .isInstanceOf(CrossDomainReferenceException.class)
                .hasMessageContaining("must match parent task domain");
    }

    @Test
    @DisplayName("Task: Personal task cannot reference an Academic course")
    void testPersonalTaskCannotReferenceCourse() {
        Course course = new Course("CS101", "Intro to CS", "Fall 2026", new BigDecimal("4.0"), new BigDecimal("90.00"));
        Task personalTask = new Task(DomainScope.PERSONAL, "Gym session", TaskPriority.LOW, Instant.now());
        personalTask.setCourse(course);

        assertThatThrownBy(personalTask::validateCrossDomainReferences)
                .isInstanceOf(CrossDomainReferenceException.class)
                .hasMessageContaining("ACADEMICS domain");
    }

    @Test
    @DisplayName("Task: Personal task cannot reference a Career application")
    void testPersonalTaskCannotReferenceCareerApplication() {
        CareerApplication app = new CareerApplication("Acme Corp", "Software Engineer", CareerStatus.APPLIED, LocalDate.now());
        Task personalTask = new Task(DomainScope.PERSONAL, "Gym session", TaskPriority.LOW, Instant.now());
        personalTask.setCareerApplication(app);

        assertThatThrownBy(personalTask::validateCrossDomainReferences)
                .isInstanceOf(CrossDomainReferenceException.class)
                .hasMessageContaining("CAREER domain");
    }

    @Test
    @DisplayName("Task: Valid cross references in ACADEMICS and CAREER pass validation")
    void testValidCrossReferences() {
        Course course = new Course("CS101", "Intro to CS", "Fall 2026", new BigDecimal("4.0"), new BigDecimal("90.00"));
        Task academicTask = new Task(DomainScope.ACADEMICS, "Study Chapter 1", TaskPriority.HIGH, Instant.now());
        academicTask.setCourse(course);
        academicTask.validateCrossDomainReferences(); // Should not throw

        CareerApplication app = new CareerApplication("Acme Corp", "Software Engineer", CareerStatus.APPLIED, LocalDate.now());
        Task careerTask = new Task(DomainScope.CAREER, "Prepare interview", TaskPriority.HIGH, Instant.now());
        careerTask.setCareerApplication(app);
        careerTask.validateCrossDomainReferences(); // Should not throw
    }

    @Test
    @DisplayName("Assessment: category must belong to the same course")
    void testAssessmentCategoryMustBelongToSameCourse() {
        Course courseA = new Course("CS101", "Intro to CS", "Fall 2026", new BigDecimal("4.0"), new BigDecimal("90.00"));
        courseA.setId(UUID.randomUUID());

        Course courseB = new Course("MATH201", "Calculus II", "Fall 2026", new BigDecimal("4.0"), new BigDecimal("90.00"));
        courseB.setId(UUID.randomUUID());

        AssessmentCategory categoryB = new AssessmentCategory(courseB, "Homework", new BigDecimal("30.00"), 1);
        Assessment assessment = new Assessment(courseA, categoryB, "HW 1", new BigDecimal("100.00"));

        // PrePersist invokes validateCategoryBelongsToCourse via onCreate
        assertThatThrownBy(assessment::onCreate)
                .isInstanceOf(CrossDomainReferenceException.class)
                .hasMessageContaining("does not belong to the assessment's course");
    }

    @Test
    @DisplayName("Habit: invalid timezone throws IllegalArgumentException")
    void testHabitInvalidTimezone() {
        Habit habit = new Habit("Reading", HabitFrequency.DAILY, 1, "Invalid/TimeZone_Zone");
        assertThatThrownBy(habit::validateTimezone)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("HabitLog: logging future date throws IllegalArgumentException")
    void testHabitLogFutureDateThrows() {
        Habit habit = new Habit("Meditation", HabitFrequency.DAILY, 1, "UTC");
        LocalDate tomorrow = LocalDate.now().plusDays(2);
        HabitLog log = new HabitLog(habit, tomorrow, Instant.now(), 1, "Notes");

        assertThatThrownBy(log::validateNotFutureDate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future date");
    }
}
