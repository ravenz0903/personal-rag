package com.tracker.rag.model;

import com.tracker.rag.domain.DomainScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "career_applications",
    indexes = {
        @Index(name = "idx_career_apps_domain", columnList = "domain"),
        @Index(name = "idx_career_apps_status", columnList = "status")
    }
)
public class CareerApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "domain", nullable = false, length = 32)
    private DomainScope domain = DomainScope.CAREER;

    @NotBlank
    @Size(min = 1, max = 255)
    @Column(name = "company", nullable = false, length = 255)
    private String company;

    @NotBlank
    @Size(min = 1, max = 255)
    @Column(name = "position", nullable = false, length = 255)
    private String position;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CareerStatus status = CareerStatus.WISHLIST;

    @Column(name = "applied_date")
    private LocalDate appliedDate;

    @Size(max = 255)
    @Column(name = "location", length = 255)
    private String location;

    @Size(max = 128)
    @Column(name = "salary_range", length = 128)
    private String salaryRange;

    @Column(name = "job_posting_url", columnDefinition = "TEXT")
    private String jobPostingUrl;

    @Size(max = 255)
    @Column(name = "contact_person", length = 255)
    private String contactPerson;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CareerApplication() {
        this.domain = DomainScope.CAREER;
    }

    public CareerApplication(String company, String position, CareerStatus status, LocalDate appliedDate) {
        this.domain = DomainScope.CAREER;
        this.company = company;
        this.position = position;
        this.status = status != null ? status : CareerStatus.WISHLIST;
        this.appliedDate = appliedDate;
    }

    @PrePersist
    protected void onCreate() {
        this.domain = DomainScope.CAREER;
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

    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }

    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }

    public CareerStatus getStatus() { return status; }
    public void setStatus(CareerStatus status) { this.status = status; }

    public LocalDate getAppliedDate() { return appliedDate; }
    public void setAppliedDate(LocalDate appliedDate) { this.appliedDate = appliedDate; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getSalaryRange() { return salaryRange; }
    public void setSalaryRange(String salaryRange) { this.salaryRange = salaryRange; }

    public String getJobPostingUrl() { return jobPostingUrl; }
    public void setJobPostingUrl(String jobPostingUrl) { this.jobPostingUrl = jobPostingUrl; }

    public String getContactPerson() { return contactPerson; }
    public void setContactPerson(String contactPerson) { this.contactPerson = contactPerson; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
