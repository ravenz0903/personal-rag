package com.tracker.rag.model;

import com.tracker.rag.domain.CrossDomainReferenceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "assessments",
    indexes = {
        @Index(name = "idx_assessments_course", columnList = "course_id"),
        @Index(name = "idx_assessments_category", columnList = "category_id")
    }
)
public class Assessment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private AssessmentCategory category;

    @NotBlank
    @Size(min = 1, max = 255)
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "due_date")
    private Instant dueDate;

    @NotNull
    @DecimalMin("0.01")
    @Column(name = "max_score", nullable = false, precision = 7, scale = 2)
    private BigDecimal maxScore;

    @DecimalMin("0.00")
    @Column(name = "earned_score", precision = 7, scale = 2)
    private BigDecimal earnedScore;

    @Column(name = "is_completed", nullable = false)
    private boolean isCompleted = false;

    @Column(name = "is_dropped", nullable = false)
    private boolean isDropped = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Assessment() {
    }

    public Assessment(Course course, AssessmentCategory category, String title, BigDecimal maxScore) {
        this.course = course;
        this.category = category;
        this.title = title;
        this.maxScore = maxScore;
    }

    @PrePersist
    protected void onCreate() {
        validateCategoryBelongsToCourse();
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        validateCategoryBelongsToCourse();
        this.updatedAt = Instant.now();
    }

    private void validateCategoryBelongsToCourse() {
        if (category != null && course != null) {
            if (category.getCourse() != null && category.getCourse().getId() != null && course.getId() != null) {
                if (!category.getCourse().getId().equals(course.getId())) {
                    throw new CrossDomainReferenceException("Assessment category does not belong to the assessment's course.");
                }
            }
        }
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }

    public AssessmentCategory getCategory() { return category; }
    public void setCategory(AssessmentCategory category) { this.category = category; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Instant getDueDate() { return dueDate; }
    public void setDueDate(Instant dueDate) { this.dueDate = dueDate; }

    public BigDecimal getMaxScore() { return maxScore; }
    public void setMaxScore(BigDecimal maxScore) { this.maxScore = maxScore; }

    public BigDecimal getEarnedScore() { return earnedScore; }
    public void setEarnedScore(BigDecimal earnedScore) { this.earnedScore = earnedScore; }

    public boolean isCompleted() { return isCompleted; }
    public void setCompleted(boolean completed) { isCompleted = completed; }

    public boolean isDropped() { return isDropped; }
    public void setDropped(boolean dropped) { isDropped = dropped; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
