package com.tracker.rag.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
    name = "assessment_categories",
    indexes = {
        @Index(name = "idx_categories_course", columnList = "course_id")
    }
)
public class AssessmentCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @NotBlank
    @Size(min = 1, max = 128)
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @NotNull
    @DecimalMin("0.01")
    @DecimalMax("100.00")
    @Column(name = "weight", nullable = false, precision = 5, scale = 2)
    private BigDecimal weight;

    @NotNull
    @Min(0)
    @Column(name = "drop_lowest_n", nullable = false)
    private int dropLowestN = 0;

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Assessment> assessments = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AssessmentCategory() {
    }

    public AssessmentCategory(Course course, String name, BigDecimal weight, int dropLowestN) {
        this.course = course;
        this.name = name;
        this.weight = weight;
        this.dropLowestN = dropLowestN;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }

    public int getDropLowestN() { return dropLowestN; }
    public void setDropLowestN(int dropLowestN) { this.dropLowestN = dropLowestN; }

    public List<Assessment> getAssessments() { return assessments; }
    public void setAssessments(List<Assessment> assessments) { this.assessments = assessments; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
