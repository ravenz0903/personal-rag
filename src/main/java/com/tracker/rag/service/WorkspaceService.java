package com.tracker.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.*;
import com.tracker.rag.dto.*;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class WorkspaceService {
    private final CourseRepository courses;
    private final AssessmentCategoryRepository categories;
    private final AssessmentRepository assessments;
    private final CareerApplicationRepository applications;
    private final EventLogService events;
    private final GradeCalculationService grades;
    private final ObjectMapper mapper;
    public WorkspaceService(CourseRepository courses, AssessmentCategoryRepository categories, AssessmentRepository assessments,
            CareerApplicationRepository applications, EventLogService events, GradeCalculationService grades, ObjectMapper mapper) {
        this.courses = courses; this.categories = categories; this.assessments = assessments;
        this.applications = applications; this.events = events; this.grades = grades; this.mapper = mapper;
    }
    public List<CourseView> courseViews() {
        return courses.findByDomain(DomainScope.ACADEMICS).stream().map(course -> {
            List<CategoryView> categoryViews = categories.findByCourseId(course.getId()).stream().map(category ->
                    new CategoryView(category.getId(), category.getName(), category.getWeight(), category.getDropLowestN())).toList();
            List<AssessmentView> assessmentViews = assessments.findByCourseId(course.getId()).stream().map(a ->
                    new AssessmentView(a.getId(), a.getCategory().getId(), a.getTitle(), a.getMaxScore(), a.getEarnedScore(), a.getDueDate())).toList();
            List<CategoryGradeInput> inputs = categoryViews.stream().map(category -> new CategoryGradeInput(category.name(), category.weight(), category.dropLowestN(),
                    assessmentViews.stream().filter(a -> a.categoryId().equals(category.id()) && a.earnedScore() != null)
                            .map(a -> a.earnedScore().multiply(new BigDecimal("100")).divide(a.maxScore(), 6, RoundingMode.HALF_UP)).toList())).toList();
            return new CourseView(course.getId(), course.getCode(), course.getName(), course.getTerm(), course.getTargetGrade(), categoryViews, assessmentViews, grades.calculateCourseGrade(inputs));
        }).toList();
    }
    @Transactional public Course createCourse(String code, String name, String term, BigDecimal target) {
        if (courses.existsByCodeIgnoreCase(code)) throw new IllegalArgumentException("A course with this code already exists");
        Course course = courses.save(new Course(code.trim().toUpperCase(Locale.ROOT), name, term, new BigDecimal("4.0"), target));
        events.recordMutation(DomainScope.ACADEMICS, EntityType.COURSE, course.getId(), ActionType.CREATE, null, course);
        return course;
    }
    @Transactional public CategoryView createCategory(UUID courseId, String name, BigDecimal weight, int drop) {
        Course course = courses.findByIdAndDomain(courseId, DomainScope.ACADEMICS).orElseThrow(() -> new IllegalArgumentException("Course not found"));
        BigDecimal total = categories.findByCourseId(courseId).stream().map(AssessmentCategory::getWeight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0 || total.add(weight).compareTo(new BigDecimal("100")) > 0)
            throw new IllegalArgumentException("Category weights must be positive and total at most 100%");
        if (drop < 0) throw new IllegalArgumentException("Drop count cannot be negative");
        if (categories.findByCourseIdAndNameIgnoreCase(courseId, name).isPresent()) throw new IllegalArgumentException("This category already exists");
        AssessmentCategory category = categories.save(new AssessmentCategory(course, name, weight, drop));
        events.recordMutation(DomainScope.ACADEMICS, EntityType.ASSESSMENT_CATEGORY, category.getId(), ActionType.CREATE, null,
                Map.of("id", category.getId(), "name", name, "weight", weight, "courseId", courseId));
        return new CategoryView(category.getId(), name, weight, drop);
    }
    @Transactional public AssessmentView scoreAssessment(UUID id, BigDecimal score) {
        Assessment assessment = assessments.findByIdAndCourseDomainIn(id, Set.of(DomainScope.ACADEMICS)).orElseThrow(() -> new IllegalArgumentException("Assessment not found"));
        if (score == null || score.signum() < 0 || score.compareTo(assessment.getMaxScore()) > 0) throw new IllegalArgumentException("Score must be between zero and the maximum score");
        Object before = mapper.convertValue(assessment, Map.class);
        assessment.setEarnedScore(score); assessment.setCompleted(true);
        assessments.save(assessment);
        events.recordMutation(DomainScope.ACADEMICS, EntityType.ASSESSMENT, id, ActionType.UPDATE, before, assessment);
        return new AssessmentView(id, assessment.getCategory().getId(), assessment.getTitle(), assessment.getMaxScore(), score, assessment.getDueDate());
    }
    @Transactional public CareerApplication updateCareerStatus(UUID id, CareerStatus status) {
        CareerApplication application = applications.findByIdAndDomain(id, DomainScope.CAREER).orElseThrow(() -> new IllegalArgumentException("Application not found"));
        Object before = mapper.convertValue(application, Map.class);
        application.setStatus(status); applications.save(application);
        events.recordMutation(DomainScope.CAREER, EntityType.CAREER_APPLICATION, id, ActionType.UPDATE, before, application);
        return application;
    }
    public record CategoryView(UUID id, String name, BigDecimal weight, int dropLowestN) {}
    public record AssessmentView(UUID id, UUID categoryId, String title, BigDecimal maxScore, BigDecimal earnedScore, Instant dueDate) {}
    public record CourseView(UUID id, String code, String name, String term, BigDecimal targetGrade, List<CategoryView> categories, List<AssessmentView> assessments, CourseGradeResult grade) {}
}
