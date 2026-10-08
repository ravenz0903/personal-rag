package com.tracker.rag.service;

import com.tracker.rag.domain.ActionType;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.dto.StreakResult;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * High-level orchestration service for structured tracker records.
 * Provides unified queries for the Today Dashboard across Academics, Personal, and Career.
 */
@Service
public class TrackerService {

    private final TaskRepository taskRepository;
    private final CourseRepository courseRepository;
    private final AssessmentRepository assessmentRepository;
    private final HabitRepository habitRepository;
    private final HabitLogRepository habitLogRepository;
    private final CareerApplicationRepository careerApplicationRepository;
    private final HabitStreakService habitStreakService;
    private final EventLogService eventLogService;

    public TrackerService(
            TaskRepository taskRepository,
            CourseRepository courseRepository,
            AssessmentRepository assessmentRepository,
            HabitRepository habitRepository,
            HabitLogRepository habitLogRepository,
            CareerApplicationRepository careerApplicationRepository,
            HabitStreakService habitStreakService,
            EventLogService eventLogService
    ) {
        this.taskRepository = taskRepository;
        this.courseRepository = courseRepository;
        this.assessmentRepository = assessmentRepository;
        this.habitRepository = habitRepository;
        this.habitLogRepository = habitLogRepository;
        this.careerApplicationRepository = careerApplicationRepository;
        this.habitStreakService = habitStreakService;
        this.eventLogService = eventLogService;
    }

    public List<Task> getTasksByDomain(DomainScope domain) {
        DomainScope.validateScope(domain);
        return taskRepository.findByDomain(domain);
    }

    public List<Task> getTasksByDomains(Set<DomainScope> domains) {
        Set<DomainScope> validated = DomainScope.validateAndResolve(domains);
        return taskRepository.findByDomainIn(validated);
    }

    @Transactional
    public Task createTask(DomainScope domain, String title, TaskPriority priority, Instant dueDate) {
        DomainScope.validateScope(domain);
        Task task = new Task(domain, title, priority != null ? priority : TaskPriority.MEDIUM, dueDate);
        Task saved = taskRepository.save(task);
        eventLogService.recordMutation(domain, EntityType.TASK, saved.getId(), ActionType.CREATE, null, saved);
        return saved;
    }

    @Transactional
    public Task toggleTaskStatus(UUID taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        Task before = new Task(task.getDomain(), task.getTitle(), task.getPriority(), task.getDueDate());
        before.setStatus(task.getStatus());

        if (task.getStatus() == TaskStatus.COMPLETED) {
            task.setStatus(TaskStatus.PENDING);
        } else {
            task.setStatus(TaskStatus.COMPLETED);
        }
        Task updated = taskRepository.save(task);
        eventLogService.recordMutation(task.getDomain(), EntityType.TASK, task.getId(), ActionType.UPDATE, before, updated);
        return updated;
    }

    public List<HabitWithStreak> getHabitsWithStreaks(DomainScope domain, ZoneId zoneId) {
        DomainScope.validateScope(domain);
        List<Habit> habits = habitRepository.findByDomain(domain);
        List<HabitWithStreak> result = new ArrayList<>();

        for (Habit habit : habits) {
            List<HabitLog> logs = habitLogRepository.findByHabitIdOrderByLogDateAsc(habit.getId());
            List<LocalDate> completionDates = logs.stream()
                    .map(HabitLog::getLogDate)
                    .toList();
            StreakResult streak = habitStreakService.computeStreak(completionDates, LocalDate.now(zoneId), zoneId);
            result.add(new HabitWithStreak(habit, streak));
        }
        return result;
    }

    @Transactional
    public HabitLog logHabit(UUID habitId, LocalDate date) {
        Habit habit = habitRepository.findById(habitId)
                .orElseThrow(() -> new IllegalArgumentException("Habit not found: " + habitId));
        LocalDate logDate = date != null ? date : LocalDate.now();
        HabitLog log = new HabitLog(habit, logDate, Instant.now(), 1, null);
        return habitLogRepository.save(log);
    }

    public List<CareerApplication> getCareerApplications(DomainScope domain) {
        DomainScope.validateScope(domain);
        return careerApplicationRepository.findByDomain(domain);
    }

    public List<Course> getCourses(DomainScope domain) {
        DomainScope.validateScope(domain);
        return courseRepository.findByDomain(domain);
    }

    /**
     * Aggregates stats for the Today View dashboard.
     */
    public TodayDashboard getTodayDashboard(Set<DomainScope> activeDomains, ZoneId zoneId) {
        Set<DomainScope> scopes = DomainScope.validateAndResolve(activeDomains);
        List<Task> pendingTasks = taskRepository.findByDomainIn(scopes).stream()
                .filter(t -> t.getStatus() != TaskStatus.COMPLETED)
                .toList();

        List<CareerApplication> applications = careerApplicationRepository.findByDomainIn(scopes);
        List<Course> courses = courseRepository.findByDomainIn(scopes);

        List<HabitWithStreak> habits = new ArrayList<>();
        if (scopes.contains(DomainScope.PERSONAL)) {
            habits = getHabitsWithStreaks(DomainScope.PERSONAL, zoneId != null ? zoneId : ZoneId.of("UTC"));
        }

        return new TodayDashboard(pendingTasks, habits, applications, courses);
    }

    public record HabitWithStreak(Habit habit, StreakResult streak) {}
    public record TodayDashboard(
            List<Task> tasks,
            List<HabitWithStreak> habits,
            List<CareerApplication> applications,
            List<Course> courses
    ) {}
}
