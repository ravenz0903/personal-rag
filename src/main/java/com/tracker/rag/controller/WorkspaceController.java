package com.tracker.rag.controller;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.model.*;
import com.tracker.rag.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {
    private final WorkspaceService workspace;
    private final EventLogService events;
    public WorkspaceController(WorkspaceService workspace, EventLogService events) { this.workspace = workspace; this.events = events; }
    @GetMapping("/courses") public List<WorkspaceService.CourseView> courses() { return workspace.courseViews(); }
    @PostMapping("/courses") public Course course(@Valid @RequestBody CourseRequest request) {
        return workspace.createCourse(request.code(), request.name(), request.term(), request.targetGrade());
    }
    @PostMapping("/courses/{id}/categories") public WorkspaceService.CategoryView category(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return workspace.createCategory(id, request.name(), request.weight(), request.dropLowestN());
    }
    @PostMapping("/assessments/{id}/score") public WorkspaceService.AssessmentView score(@PathVariable UUID id, @RequestBody ScoreRequest request) {
        return workspace.scoreAssessment(id, request.score());
    }
    @PostMapping("/career/{id}/status") public CareerApplication status(@PathVariable UUID id, @RequestBody StatusRequest request) {
        return workspace.updateCareerStatus(id, CareerStatus.valueOf(request.status()));
    }
    @GetMapping("/events") public List<EventLogEntry> events(@RequestParam String domain) { return events.getRecentEventsByDomain(DomainScope.parse(domain), 30); }
    public record CourseRequest(@NotBlank @Size(max=32) String code, @NotBlank @Size(max=255) String name,
            @NotBlank @Size(max=64) String term, @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal targetGrade) {}
    public record CategoryRequest(@NotBlank @Size(max=128) String name, @NotNull BigDecimal weight, @Min(0) int dropLowestN) {}
    public record ScoreRequest(BigDecimal score) {}
    public record StatusRequest(String status) {}
}
