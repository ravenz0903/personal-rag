package com.tracker.rag.repository;

import com.tracker.rag.domain.ActionType;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RepositoryDomainIsolationIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private HabitRepository habitRepository;

    @Autowired
    private CareerApplicationRepository careerRepository;

    @Autowired
    private EventLogRepository eventLogRepository;

    @Test
    @DisplayName("TaskRepository: Strict domain isolation prevents personal tasks leakage")
    void testTaskDomainIsolation() {
        Task acadTask = new Task(DomainScope.ACADEMICS, "Study Calculus", TaskPriority.HIGH, Instant.now());
        Task persTask = new Task(DomainScope.PERSONAL, "Buy groceries", TaskPriority.LOW, Instant.now());
        Task careerTask = new Task(DomainScope.CAREER, "Submit resume", TaskPriority.MEDIUM, Instant.now());

        taskRepository.saveAll(List.of(acadTask, persTask, careerTask));

        // Scoped to ACADEMICS: strictly 0 personal tasks
        List<Task> acadResults = taskRepository.findByDomain(DomainScope.ACADEMICS);
        assertThat(acadResults).hasSize(1);
        assertThat(acadResults.get(0).getTitle()).isEqualTo("Study Calculus");

        // Scoped to PUBLIC_DEFAULT [ACADEMICS, CAREER]: strictly 0 personal tasks
        List<Task> defaultResults = taskRepository.findByDomainIn(DomainScope.PUBLIC_DEFAULT);
        assertThat(defaultResults).hasSize(2);
        assertThat(defaultResults).noneMatch(t -> t.getDomain() == DomainScope.PERSONAL);

        // Explicit opt-in for PERSONAL
        List<Task> persResults = taskRepository.findByDomain(DomainScope.PERSONAL);
        assertThat(persResults).hasSize(1);
        assertThat(persResults.get(0).getTitle()).isEqualTo("Buy groceries");
    }

    @Test
    @DisplayName("CourseRepository: Courses are isolated by domain")
    void testCourseDomainIsolation() {
        Course course = new Course("CS101", "Computer Science I", "Fall 2026", new BigDecimal("4.0"), new BigDecimal("90.00"));
        courseRepository.save(course);

        List<Course> acadCourses = courseRepository.findByDomain(DomainScope.ACADEMICS);
        assertThat(acadCourses).hasSize(1);

        List<Course> persCourses = courseRepository.findByDomain(DomainScope.PERSONAL);
        assertThat(persCourses).isEmpty();
    }

    @Test
    @DisplayName("HabitRepository: Habits scoped to PERSONAL are isolated from ACADEMICS")
    void testHabitDomainIsolation() {
        Habit habit = new Habit("Meditation", HabitFrequency.DAILY, 1, "UTC");
        habitRepository.save(habit);

        List<Habit> personalHabits = habitRepository.findByDomain(DomainScope.PERSONAL);
        assertThat(personalHabits).hasSize(1);

        List<Habit> acadHabits = habitRepository.findByDomain(DomainScope.ACADEMICS);
        assertThat(acadHabits).isEmpty();
    }

    @Test
    @DisplayName("CareerApplicationRepository: Career applications isolated by domain")
    void testCareerDomainIsolation() {
        CareerApplication app = new CareerApplication("Tech Corp", "Backend Engineer", CareerStatus.APPLIED, LocalDate.now());
        careerRepository.save(app);

        List<CareerApplication> careerApps = careerRepository.findByDomain(DomainScope.CAREER);
        assertThat(careerApps).hasSize(1);

        List<CareerApplication> acadApps = careerRepository.findByDomain(DomainScope.ACADEMICS);
        assertThat(acadApps).isEmpty();
    }

    @Test
    @DisplayName("EventLogRepository: Event logs strictly isolated by domain")
    void testEventLogDomainIsolation() {
        EventLogEntry e1 = new EventLogEntry(
                UUID.randomUUID(), DomainScope.ACADEMICS, EntityType.TASK, UUID.randomUUID(),
                ActionType.CREATE, Instant.now(), null, "{}"
        );
        EventLogEntry e2 = new EventLogEntry(
                UUID.randomUUID(), DomainScope.PERSONAL, EntityType.HABIT, UUID.randomUUID(),
                ActionType.CREATE, Instant.now(), null, "{}"
        );

        eventLogRepository.saveAll(List.of(e1, e2));

        List<EventLogEntry> acadEvents = eventLogRepository.findByDomain(DomainScope.ACADEMICS);
        assertThat(acadEvents).hasSize(1);
        assertThat(acadEvents.get(0).getId()).isEqualTo(e1.getId());

        List<EventLogEntry> persEvents = eventLogRepository.findByDomain(DomainScope.PERSONAL);
        assertThat(persEvents).hasSize(1);
        assertThat(persEvents.get(0).getId()).isEqualTo(e2.getId());

        List<EventLogEntry> defaultEvents = eventLogRepository.findByDomainIn(DomainScope.PUBLIC_DEFAULT);
        assertThat(defaultEvents).hasSize(1);
        assertThat(defaultEvents.get(0).getId()).isEqualTo(e1.getId());
    }
}
