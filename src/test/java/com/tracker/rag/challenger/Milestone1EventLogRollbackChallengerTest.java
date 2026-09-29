package com.tracker.rag.challenger;

import com.tracker.rag.domain.*;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.*;
import com.tracker.rag.service.EventLogService;
import com.tracker.rag.service.RollbackService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Empirical and adversarial challenge test suite for Milestone 1 Event Log & Rollback Engine.
 * Tests real JPA entities, repositories, dispatcher, and rollback handlers against the database.
 * Authored by Challenger 2 (teamwork_preview_challenger).
 */
@SpringBootTest
@DisplayName("Challenger 2: Event Log & Rollback Engine Adversarial Stress Suite")
public class Milestone1EventLogRollbackChallengerTest {

    @Autowired
    private EventLogService eventLogService;

    @Autowired
    private RollbackService rollbackService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private HabitRepository habitRepository;

    @Autowired
    private CareerApplicationRepository careerRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private EventLogRepository eventLogRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
        habitRepository.deleteAll();
        careerRepository.deleteAll();
        courseRepository.deleteAll();
        eventLogRepository.deleteAll();
    }

    @Nested
    @DisplayName("Rollback of Entity Mutations (CREATE, UPDATE, DELETE)")
    class EntityMutationRollbackTests {

        @Test
        @Transactional
        @DisplayName("Rollback of CREATE: deletes the created entity from the database")
        void testRollbackCreationDeletesEntity() {
            Task task = new Task(DomainScope.ACADEMICS, "Algorithmic Analysis HW", TaskPriority.HIGH, Instant.now());
            Task saved = taskRepository.save(task);
            entityManager.flush();

            EventLogEntry createEvent = eventLogService.recordMutation(
                DomainScope.ACADEMICS, EntityType.TASK, saved.getId(), ActionType.CREATE, null, saved
            );

            assertThat(taskRepository.findById(saved.getId())).isPresent();

            EventLogEntry rollbackResult = rollbackService.rollbackLastMutation(DomainScope.ACADEMICS);
            entityManager.flush();
            entityManager.clear();

            // Entity should be deleted
            assertThat(taskRepository.findById(saved.getId())).isEmpty();

            // Target event must be marked undone
            EventLogEntry reloadedCreate = eventLogRepository.findById(createEvent.getId()).orElseThrow();
            assertThat(reloadedCreate.isUndone()).isTrue();
            assertThat(reloadedCreate.getUndoneByEventId()).isEqualTo(rollbackResult.getId());

            // Rollback event must exist in audit log
            assertThat(rollbackResult.getAction()).isEqualTo(ActionType.ROLLBACK);
            assertThat(rollbackResult.getDomain()).isEqualTo(DomainScope.ACADEMICS);
            assertThat(rollbackResult.getEntityId()).isEqualTo(saved.getId());
        }

        @Test
        @Transactional
        @DisplayName("Rollback of DELETE: re-inserts the deleted entity with original state")
        void testRollbackDeletionRestoresEntity() {
            Habit habit = new Habit("Meditation", HabitFrequency.DAILY, 1, "UTC");
            Habit saved = habitRepository.save(habit);
            entityManager.flush();

            UUID habitId = saved.getId();

            // Simulate deletion
            eventLogService.recordMutation(
                DomainScope.PERSONAL, EntityType.HABIT, habitId, ActionType.DELETE, saved, null
            );
            habitRepository.deleteById(habitId);
            entityManager.flush();
            entityManager.clear();

            assertThat(habitRepository.findById(habitId)).isEmpty();

            // Execute rollback of DELETE
            EventLogEntry rollbackResult = rollbackService.rollbackLastMutation(DomainScope.PERSONAL);
            entityManager.flush();
            entityManager.clear();

            // CRITICAL BUG VERIFICATION:
            // Hibernate's @GeneratedValue(strategy = GenerationType.UUID) causes repository.save()
            // to generate a new UUID for the restored entity on INSERT, losing the original entity identity.
            List<Habit> allHabits = habitRepository.findAll();
            assertThat(allHabits).hasSize(1);

            Habit restored = allHabits.get(0);
            assertThat(restored.getName()).isEqualTo("Meditation");
            assertThat(restored.getFrequency()).isEqualTo(HabitFrequency.DAILY);
            assertThat(restored.getTimezone()).isEqualTo("UTC");
            assertThat(restored.getDomain()).isEqualTo(DomainScope.PERSONAL);
            assertThat(rollbackResult.getAction()).isEqualTo(ActionType.ROLLBACK);

            // Empirical proof: original habitId lookup fails because id was overwritten with a new UUID
            assertThat(habitRepository.findById(habitId))
                .as("CRITICAL FLAW: Entity was restored with a newly generated UUID, original ID lookup fails")
                .isEmpty();
            assertThat(restored.getId())
                .as("CRITICAL FLAW: Restored entity UUID does not match original UUID")
                .isNotEqualTo(habitId);
        }

        @Test
        @Transactional
        @DisplayName("Rollback of UPDATE: reverts entity properties to payloadBefore")
        void testRollbackUpdateRevertsState() {
            CareerApplication app = new CareerApplication("Acme Corp", "Backend Dev", CareerStatus.APPLIED, LocalDate.now());
            CareerApplication saved = careerRepository.save(app);
            entityManager.flush();

            UUID appId = saved.getId();

            // Prepare before/after for update
            CareerApplication beforeUpdate = new CareerApplication("Acme Corp", "Backend Dev", CareerStatus.APPLIED, LocalDate.now());
            beforeUpdate.setId(appId);

            saved.setStatus(CareerStatus.INTERVIEW_SCHEDULED);
            saved.setPosition("Senior Backend Dev");
            CareerApplication updated = careerRepository.save(saved);
            entityManager.flush();

            eventLogService.recordMutation(
                DomainScope.CAREER, EntityType.CAREER_APPLICATION, appId, ActionType.UPDATE, beforeUpdate, updated
            );

            // Rollback UPDATE
            EventLogEntry rollbackResult = rollbackService.rollbackLastMutation(DomainScope.CAREER);
            entityManager.flush();
            entityManager.clear();

            CareerApplication reverted = careerRepository.findById(appId).orElseThrow();
            assertThat(reverted.getStatus()).isEqualTo(CareerStatus.APPLIED);
            assertThat(reverted.getPosition()).isEqualTo("Backend Dev");
            assertThat(rollbackResult.getAction()).isEqualTo(ActionType.ROLLBACK);
        }

        @Test
        @Transactional
        @DisplayName("Rollback of Course creation: Course deleted from repository")
        void testRollbackCourseCreation() {
            Course course = new Course("MATH201", "Linear Algebra", "Spring 2026", new BigDecimal("4.0"), new BigDecimal("95.00"));
            Course saved = courseRepository.save(course);
            entityManager.flush();

            eventLogService.recordMutation(
                DomainScope.ACADEMICS, EntityType.COURSE, saved.getId(), ActionType.CREATE, null, saved
            );

            assertThat(courseRepository.findById(saved.getId())).isPresent();

            rollbackService.rollbackLastMutation(DomainScope.ACADEMICS);
            entityManager.flush();
            entityManager.clear();

            assertThat(courseRepository.findById(saved.getId())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Rollback Rejection & Error Paths")
    class RollbackRejectionTests {

        @Test
        @Transactional
        @DisplayName("Rolling back an already rolled-back event is rejected with EventAlreadyRolledBackException")
        void testRejectAlreadyRolledBackEvent() {
            Task task = new Task(DomainScope.ACADEMICS, "Single Task", TaskPriority.LOW, Instant.now());
            Task saved = taskRepository.save(task);
            entityManager.flush();

            EventLogEntry createEvent = eventLogService.recordMutation(
                DomainScope.ACADEMICS, EntityType.TASK, saved.getId(), ActionType.CREATE, null, saved
            );

            // First rollback succeeds
            rollbackService.rollbackEvent(createEvent.getId());
            entityManager.flush();

            // Second rollback of the same event must be rejected
            assertThatThrownBy(() -> rollbackService.rollbackEvent(createEvent.getId()))
                .isInstanceOf(EventAlreadyRolledBackException.class)
                .hasMessageContaining(createEvent.getId().toString());
        }

        @Test
        @Transactional
        @DisplayName("Rolling back a ROLLBACK event is rejected with UnsupportedOperationException")
        void testRejectRollingBackRollbackEvent() {
            Task task = new Task(DomainScope.ACADEMICS, "Task To Undo", TaskPriority.MEDIUM, Instant.now());
            Task saved = taskRepository.save(task);
            entityManager.flush();

            eventLogService.recordMutation(
                DomainScope.ACADEMICS, EntityType.TASK, saved.getId(), ActionType.CREATE, null, saved
            );

            EventLogEntry rollbackEvent = rollbackService.rollbackLastMutation(DomainScope.ACADEMICS);
            entityManager.flush();

            // Attempting to rollback the rollback event itself
            assertThatThrownBy(() -> rollbackService.rollbackEvent(rollbackEvent.getId()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Cannot roll back an existing ROLLBACK event");
        }

        @Test
        @DisplayName("Rolling back with no mutations throws NoMutationsToRollbackException")
        void testRollbackEmptyHistoryThrows() {
            assertThatThrownBy(() -> rollbackService.rollbackLastMutation(DomainScope.PERSONAL))
                .isInstanceOf(NoMutationsToRollbackException.class)
                .hasMessageContaining("PERSONAL");
        }

        @Test
        @DisplayName("Rolling back non-existent eventId throws EventNotFoundException")
        void testRollbackNonExistentEventThrows() {
            UUID randomId = UUID.randomUUID();
            assertThatThrownBy(() -> rollbackService.rollbackEvent(randomId))
                .isInstanceOf(EventNotFoundException.class)
                .hasMessageContaining(randomId.toString());
        }
    }

    @Nested
    @DisplayName("Rollback Idempotency & Sequential Rollback Stack")
    class RollbackStackAndIdempotencyTests {

        @Test
        @Transactional
        @DisplayName("Sequential rollbacks pop mutations in strict LIFO order until exhausted")
        void testSequentialRollbackLIFOOrder() {
            Task t1 = taskRepository.save(new Task(DomainScope.ACADEMICS, "Task 1", TaskPriority.LOW, Instant.now()));
            Task t2 = taskRepository.save(new Task(DomainScope.ACADEMICS, "Task 2", TaskPriority.MEDIUM, Instant.now()));
            Task t3 = taskRepository.save(new Task(DomainScope.ACADEMICS, "Task 3", TaskPriority.HIGH, Instant.now()));
            entityManager.flush();

            eventLogService.recordMutation(DomainScope.ACADEMICS, EntityType.TASK, t1.getId(), ActionType.CREATE, null, t1);
            eventLogService.recordMutation(DomainScope.ACADEMICS, EntityType.TASK, t2.getId(), ActionType.CREATE, null, t2);
            eventLogService.recordMutation(DomainScope.ACADEMICS, EntityType.TASK, t3.getId(), ActionType.CREATE, null, t3);
            entityManager.flush();

            // Rollback 1: undos t3
            EventLogEntry rb1 = rollbackService.rollbackLastMutation(DomainScope.ACADEMICS);
            assertThat(rb1.getEntityId()).isEqualTo(t3.getId());
            entityManager.flush();
            assertThat(taskRepository.findById(t3.getId())).isEmpty();
            assertThat(taskRepository.findById(t2.getId())).isPresent();
            assertThat(taskRepository.findById(t1.getId())).isPresent();

            // Rollback 2: undos t2
            EventLogEntry rb2 = rollbackService.rollbackLastMutation(DomainScope.ACADEMICS);
            assertThat(rb2.getEntityId()).isEqualTo(t2.getId());
            entityManager.flush();
            assertThat(taskRepository.findById(t2.getId())).isEmpty();
            assertThat(taskRepository.findById(t1.getId())).isPresent();

            // Rollback 3: undos t1
            EventLogEntry rb3 = rollbackService.rollbackLastMutation(DomainScope.ACADEMICS);
            assertThat(rb3.getEntityId()).isEqualTo(t1.getId());
            entityManager.flush();
            assertThat(taskRepository.findById(t1.getId())).isEmpty();

            // Rollback 4: no mutations remain, must throw
            assertThatThrownBy(() -> rollbackService.rollbackLastMutation(DomainScope.ACADEMICS))
                .isInstanceOf(NoMutationsToRollbackException.class);
        }

        @Test
        @Transactional
        @DisplayName("Event log is strictly append-only: audit log count monotonically grows after rollbacks")
        void testAuditLogMonotonicGrowth() {
            Task task = taskRepository.save(new Task(DomainScope.CAREER, "Job App Followup", TaskPriority.HIGH, Instant.now()));
            entityManager.flush();

            eventLogService.recordMutation(DomainScope.CAREER, EntityType.TASK, task.getId(), ActionType.CREATE, null, task);
            assertThat(eventLogRepository.count()).isEqualTo(1);

            rollbackService.rollbackLastMutation(DomainScope.CAREER);
            entityManager.flush();

            // Total events should now be 2 (1 CREATE + 1 ROLLBACK)
            assertThat(eventLogRepository.count()).isEqualTo(2);

            List<EventLogEntry> allEvents = eventLogRepository.findByDomain(DomainScope.CAREER);
            assertThat(allEvents).hasSize(2);
            assertThat(allEvents.stream().anyMatch(e -> e.getAction() == ActionType.CREATE)).isTrue();
            assertThat(allEvents.stream().anyMatch(e -> e.getAction() == ActionType.ROLLBACK)).isTrue();
        }
    }

    @Nested
    @DisplayName("Domain Scoping & Strict Domain Filtering on Event Log")
    class DomainFilteringTests {

        @Test
        @Transactional
        @DisplayName("Rollback in ACADEMICS strictly never touches or undoes PERSONAL mutations")
        void testRollbackDomainStrictIsolation() {
            Habit personalHabit = habitRepository.save(new Habit("Workout", HabitFrequency.DAILY, 1, "UTC"));
            entityManager.flush();

            eventLogService.recordMutation(
                DomainScope.PERSONAL, EntityType.HABIT, personalHabit.getId(), ActionType.CREATE, null, personalHabit
            );

            // Attempt rollback in ACADEMICS: should find no mutations in ACADEMICS
            assertThatThrownBy(() -> rollbackService.rollbackLastMutation(DomainScope.ACADEMICS))
                .isInstanceOf(NoMutationsToRollbackException.class)
                .hasMessageContaining("ACADEMICS");

            // Personal habit remains untouched in DB
            assertThat(habitRepository.findById(personalHabit.getId())).isPresent();

            // Personal event remains active
            List<EventLogEntry> personalEvents = eventLogService.getRecentEventsByDomain(DomainScope.PERSONAL, 10);
            assertThat(personalEvents).hasSize(1);
            assertThat(personalEvents.get(0).isUndone()).isFalse();
        }

        @Test
        @DisplayName("Fail-closed validation on null or empty scopes for event log and rollback")
        void testFailClosedScopeValidation() {
            assertThatThrownBy(() -> rollbackService.rollbackLastMutation(null))
                .isInstanceOf(UnscopedQueryException.class);

            assertThatThrownBy(() -> eventLogService.recordMutation(
                null, EntityType.TASK, UUID.randomUUID(), ActionType.CREATE, null, "{}"
            )).isInstanceOf(UnscopedQueryException.class);

            assertThatThrownBy(() -> eventLogService.getRecentEventsByDomain(null, 10))
                .isInstanceOf(UnscopedQueryException.class);

            assertThatThrownBy(() -> eventLogService.getRecentEventsByDomains(Set.of(), 10))
                .isInstanceOf(UnscopedQueryException.class);
        }

        @Test
        @Transactional
        @DisplayName("Domain-scoped recent events query filters out cross-domain log entries")
        void testRecentEventsDomainFiltering() {
            eventLogService.recordEvent(DomainScope.ACADEMICS, EntityType.TASK, UUID.randomUUID(), ActionType.CREATE, null, "{}");
            eventLogService.recordEvent(DomainScope.PERSONAL, EntityType.HABIT, UUID.randomUUID(), ActionType.CREATE, null, "{}");
            eventLogService.recordEvent(DomainScope.CAREER, EntityType.CAREER_APPLICATION, UUID.randomUUID(), ActionType.CREATE, null, "{}");
            entityManager.flush();

            List<EventLogEntry> acadEvents = eventLogService.getRecentEventsByDomain(DomainScope.ACADEMICS, 10);
            assertThat(acadEvents).hasSize(1);
            assertThat(acadEvents.get(0).getDomain()).isEqualTo(DomainScope.ACADEMICS);

            List<EventLogEntry> publicEvents = eventLogService.getRecentEventsByDomains(DomainScope.PUBLIC_DEFAULT, 10);
            assertThat(publicEvents).hasSize(2);
            assertThat(publicEvents).noneMatch(e -> e.getDomain() == DomainScope.PERSONAL);
        }
    }
}
