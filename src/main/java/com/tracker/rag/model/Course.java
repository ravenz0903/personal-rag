package com.tracker.rag.model;

import com.tracker.rag.domain.DomainScope;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
    name = "courses",
    indexes = {
        @Index(name = "idx_courses_domain", columnList = "domain"),
        @Index(name = "idx_courses_code", columnList = "code", unique = true)
    }
)
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "domain", nullable = false, length = 32)
    private DomainScope domain = DomainScope.ACADEMICS;

    @NotBlank
    @Size(min = 1, max = 32)
    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    @NotBlank
    @Size(min = 1, max = 255)
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @NotBlank
    @Size(min = 1, max = 64)
    @Column(name = "term", nullable = false, length = 64)
    private String term;

    @NotNull
    @DecimalMin("0.0")
    @Column(name = "credits", nullable = false, precision = 3, scale = 1)
    private BigDecimal credits = new BigDecimal("4.0");

    @NotNull
    @DecimalMin("0.00")
    @DecimalMax("100.00")
    @Column(name = "target_grade", nullable = false, precision = 5, scale = 2)
    private BigDecimal targetGrade = new BigDecimal("90.00");

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AssessmentCategory> categories = new ArrayList<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Assessment> assessments = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Course() {
        this.domain = DomainScope.ACADEMICS;
    }

    public Course(String code, String name, String term, BigDecimal credits, BigDecimal targetGrade) {
        this.domain = DomainScope.ACADEMICS;
        this.code = code;
        this.name = name;
        this.term = term;
        this.credits = credits != null ? credits : new BigDecimal("4.0");
        this.targetGrade = targetGrade != null ? targetGrade : new BigDecimal("90.00");
    }

    @PrePersist
    protected void onCreate() {
        this.domain = DomainScope.ACADEMICS;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public DomainScope getDomain() { return domain; }
    public void setDomain(DomainScope domain) { this.domain = domain; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTerm() { return term; }
    public void setTerm(String term) { this.term = term; }

    public BigDecimal getCredits() { return credits; }
    public void setCredits(BigDecimal credits) { this.credits = credits; }

    public BigDecimal getTargetGrade() { return targetGrade; }
    public void setTargetGrade(BigDecimal targetGrade) { this.targetGrade = targetGrade; }

    public List<AssessmentCategory> getCategories() { return categories; }
    public void setCategories(List<AssessmentCategory> categories) { this.categories = categories; }

    public List<Assessment> getAssessments() { return assessments; }
    public void setAssessments(List<Assessment> assessments) { this.assessments = assessments; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
