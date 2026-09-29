package com.tracker.rag.e2e.tier1;

import com.tracker.rag.e2e.contract.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tier 1: Task Management and Career Pipeline Tests (F04, F10)")
public class Tier1TaskAndCareerPipelineTest {

    @Nested
    @DisplayName("F04: Deterministic Task Management Tests")
    class TaskManagementTests {

        @Test
        @DisplayName("TC-F04-01: Create Task with required attributes and defaults")
        void testCreateTaskDefaults() {
            Instant due = Instant.now().plusSeconds(86400);
            TaskRecord task = TaskRecord.create(DomainScope.ACADEMICS, "Read Chapter 4", TaskPriority.HIGH, due);

            assertNotNull(task.id());
            assertEquals(DomainScope.ACADEMICS, task.domain());
            assertEquals("Read Chapter 4", task.title());
            assertEquals(TaskPriority.HIGH, task.priority());
            assertEquals(TaskStatus.PENDING, task.status());
            assertEquals(due, task.dueDate());
            assertNull(task.completedAt(), "Pending task completedAt must be null");
        }

        @Test
        @DisplayName("TC-F04-02: Valid Task Lifecycle PENDING -> IN_PROGRESS -> COMPLETED")
        void testValidTaskLifecycle() {
            TaskRecord task = TaskRecord.create(DomainScope.CAREER, "Update Resume", TaskPriority.MEDIUM, null);

            TaskRecord inProgress = task.withStatus(TaskStatus.IN_PROGRESS);
            assertEquals(TaskStatus.IN_PROGRESS, inProgress.status());
            assertNull(inProgress.completedAt());

            TaskRecord completed = inProgress.withStatus(TaskStatus.COMPLETED);
            assertEquals(TaskStatus.COMPLETED, completed.status());
            assertNotNull(completed.completedAt(), "Completed task must set completedAt timestamp");
        }

        @Test
        @DisplayName("TC-F04-03: Reopening COMPLETED task to PENDING resets completedAt")
        void testReopenCompletedTask() {
            TaskRecord task = TaskRecord.create(DomainScope.PERSONAL, "Gym Session", TaskPriority.LOW, null)
                    .withStatus(TaskStatus.COMPLETED);
            assertNotNull(task.completedAt());

            TaskRecord reopened = task.withStatus(TaskStatus.PENDING);
            assertEquals(TaskStatus.PENDING, reopened.status());
            assertNull(reopened.completedAt(), "Reopened task must clear completedAt");
        }

        @Test
        @DisplayName("TC-F04-04: Cannot transition directly from CANCELLED to COMPLETED")
        void testCancelledCannotCompleteDirectly() {
            TaskRecord task = TaskRecord.create(DomainScope.ACADEMICS, "Optional Quiz", TaskPriority.LOW, null)
                    .withStatus(TaskStatus.CANCELLED);

            assertThrows(InvalidStateTransitionException.class, () -> task.withStatus(TaskStatus.COMPLETED));
        }

        @Test
        @DisplayName("TC-F04-05: Task title validation rejects blank or null")
        void testTaskTitleValidation() {
            assertThrows(IllegalArgumentException.class, () -> TaskRecord.create(DomainScope.PERSONAL, "", TaskPriority.LOW, null));
            assertThrows(IllegalArgumentException.class, () -> TaskRecord.create(DomainScope.PERSONAL, "   ", TaskPriority.LOW, null));
            assertThrows(IllegalArgumentException.class, () -> TaskRecord.create(DomainScope.PERSONAL, null, TaskPriority.LOW, null));
        }
    }

    @Nested
    @DisplayName("F10: Career Pipeline Tracking Tests")
    class CareerPipelineTests {

        @Test
        @DisplayName("TC-F10-01: Create Career Application in WISHLIST state")
        void testCreateCareerApp() {
            CareerApplicationRecord app = CareerApplicationRecord.create("Anthropic", "Research Engineer", CareerStatus.WISHLIST);

            assertNotNull(app.id());
            assertEquals(DomainScope.CAREER, app.domain());
            assertEquals("Anthropic", app.company());
            assertEquals("Research Engineer", app.position());
            assertEquals(CareerStatus.WISHLIST, app.status());
            assertNotNull(app.appliedDate());
        }

        @Test
        @DisplayName("TC-F10-02: Progressive Career Pipeline Progression")
        void testCareerProgression() {
            CareerApplicationRecord app = CareerApplicationRecord.create("Stripe", "Backend Engineer", CareerStatus.WISHLIST);

            CareerApplicationRecord applied = app.withStatus(CareerStatus.APPLIED);
            assertEquals(CareerStatus.APPLIED, applied.status());

            CareerApplicationRecord oa = applied.withStatus(CareerStatus.OA_RECEIVED);
            assertEquals(CareerStatus.OA_RECEIVED, oa.status());

            CareerApplicationRecord interview = oa.withStatus(CareerStatus.INTERVIEW_SCHEDULED);
            assertEquals(CareerStatus.INTERVIEW_SCHEDULED, interview.status());

            CareerApplicationRecord offer = interview.withStatus(CareerStatus.OFFER);
            assertEquals(CareerStatus.OFFER, offer.status());
        }

        @Test
        @DisplayName("TC-F10-03: Rejection from Applied or Interview stage")
        void testRejectionTransition() {
            CareerApplicationRecord app = CareerApplicationRecord.create("Amazon", "SDE II", CareerStatus.APPLIED);
            CareerApplicationRecord rejected = app.withStatus(CareerStatus.REJECTED);

            assertEquals(CareerStatus.REJECTED, rejected.status());
            assertTrue(rejected.status().isTerminal());
        }

        @Test
        @DisplayName("TC-F10-04: Cannot transition from Terminal REJECTED or WITHDRAWN state")
        void testTerminalStateCannotTransition() {
            CareerApplicationRecord rejected = CareerApplicationRecord.create("Netflix", "Senior SWE", CareerStatus.APPLIED)
                    .withStatus(CareerStatus.REJECTED);

            assertThrows(InvalidStateTransitionException.class, () -> rejected.withStatus(CareerStatus.INTERVIEW_SCHEDULED));
            assertThrows(InvalidStateTransitionException.class, () -> rejected.withStatus(CareerStatus.OFFER));

            CareerApplicationRecord withdrawn = CareerApplicationRecord.create("Meta", "SWE", CareerStatus.WISHLIST)
                    .withStatus(CareerStatus.WITHDRAWN);
            assertThrows(InvalidStateTransitionException.class, () -> withdrawn.withStatus(CareerStatus.APPLIED));
        }

        @Test
        @DisplayName("TC-F10-05: Validation rejects blank company or position")
        void testCareerValidation() {
            assertThrows(IllegalArgumentException.class, () -> CareerApplicationRecord.create("", "Engineer", CareerStatus.APPLIED));
            assertThrows(IllegalArgumentException.class, () -> CareerApplicationRecord.create("Google", "   ", CareerStatus.APPLIED));
            assertThrows(NullPointerException.class, () -> new CareerApplicationRecord(
                    UUID.randomUUID(), null, "Google", "SWE", CareerStatus.APPLIED, LocalDate.now(), null, null, null, null
            ));
        }
    }
}
