package com.tracker.rag.controller;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.model.CareerApplication;
import com.tracker.rag.model.Course;
import com.tracker.rag.model.HabitLog;
import com.tracker.rag.model.Task;
import com.tracker.rag.model.TaskPriority;
import com.tracker.rag.service.TrackerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/tracker")
@CrossOrigin(origins = "*")
public class TrackerController {

    private final TrackerService trackerService;

    public TrackerController(TrackerService trackerService) {
        this.trackerService = trackerService;
    }

    @GetMapping("/today")
    public ResponseEntity<TrackerService.TodayDashboard> getTodayDashboard(
            @RequestParam(required = false) List<String> domains,
            @RequestParam(required = false, defaultValue = "UTC") String timezone
    ) {
        Set<DomainScope> scopes = (domains != null && !domains.isEmpty())
                ? DomainScope.parseScopes(domains)
                : DomainScope.defaultGeneralScope();
        return ResponseEntity.ok(trackerService.getTodayDashboard(scopes, ZoneId.of(timezone)));
    }

    @GetMapping("/tasks")
    public ResponseEntity<List<Task>> getTasks(@RequestParam String domain) {
        DomainScope scope = DomainScope.parse(domain);
        return ResponseEntity.ok(trackerService.getTasksByDomain(scope));
    }

    @PostMapping("/tasks")
    public ResponseEntity<Task> createTask(@RequestBody CreateTaskRequest req) {
        DomainScope scope = DomainScope.parse(req.domain());
        Instant due = req.dueDate() != null ? Instant.parse(req.dueDate()) : Instant.now().plusSeconds(86400);
        TaskPriority priority = req.priority() != null ? TaskPriority.valueOf(req.priority().toUpperCase()) : TaskPriority.MEDIUM;
        Task created = trackerService.createTask(scope, req.title(), priority, due);
        return ResponseEntity.ok(created);
    }

    @PostMapping("/tasks/{id}/toggle")
    public ResponseEntity<Task> toggleTask(@PathVariable UUID id) {
        return ResponseEntity.ok(trackerService.toggleTaskStatus(id));
    }

    @GetMapping("/habits")
    public ResponseEntity<List<TrackerService.HabitWithStreak>> getHabits(
            @RequestParam(defaultValue = "PERSONAL") String domain,
            @RequestParam(defaultValue = "UTC") String timezone
    ) {
        DomainScope scope = DomainScope.parse(domain);
        return ResponseEntity.ok(trackerService.getHabitsWithStreaks(scope, ZoneId.of(timezone)));
    }

    @PostMapping("/habits/{id}/log")
    public ResponseEntity<HabitLog> logHabit(
            @PathVariable UUID id,
            @RequestParam(required = false) String date
    ) {
        LocalDate logDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        return ResponseEntity.ok(trackerService.logHabit(id, logDate));
    }

    @GetMapping("/career")
    public ResponseEntity<List<CareerApplication>> getCareerApplications(
            @RequestParam(defaultValue = "CAREER") String domain
    ) {
        DomainScope scope = DomainScope.parse(domain);
        return ResponseEntity.ok(trackerService.getCareerApplications(scope));
    }

    @GetMapping("/courses")
    public ResponseEntity<List<Course>> getCourses(
            @RequestParam(defaultValue = "ACADEMICS") String domain
    ) {
        DomainScope scope = DomainScope.parse(domain);
        return ResponseEntity.ok(trackerService.getCourses(scope));
    }

    public record CreateTaskRequest(String domain, String title, String priority, String dueDate) {}
}
