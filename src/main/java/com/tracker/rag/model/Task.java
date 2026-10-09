package com.tracker.rag.model;

import com.tracker.rag.domain.CrossDomainReferenceException;
import com.tracker.rag.domain.DomainScope;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
    name = "tasks",
    indexes = {
        @Index(name = "idx_tasks_domain", columnList = "domain"),
        @Index(name = "idx_tasks_status", columnList = "status"),
        @Index(name = "idx_tasks_due_date", columnList = "due_date")
    }
)
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "domain", nullable = false, length = 32)
    private DomainScope domain;

    @NotBlank
    @Size(min = 1, max = 255)
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "due_date")
    private Instant dueDate;

    @Column(name = "remind_at")
    private Instant remindAt;

    public Instant getRemindAt() { return remindAt; }
    public void setRemindAt(Instant remindAt) { this.remindAt = remindAt; }

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private TaskPriority priority = TaskPriority.MEDIUM;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "completed_at")
    private Instant completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_task_id")
    private Task parentTask;

    @OneToMany(mappedBy = "parentTask", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Task> subtasks = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "career_application_id")
    private CareerApplication careerApplication;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Task() {
    }

    public Task(DomainScope domain, String title, TaskPriority priority, Instant dueDate) {
        this.domain = domain;
        this.title = title;
        this.priority = priority != null ? priority : TaskPriority.MEDIUM;
        this.dueDate = dueDate;
        this.status = TaskStatus.PENDING;
    }

    @PrePersist
    protected void onCreate() {
        validateCrossDomainReferences();
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        syncCompletionTimestamp();
    }

    @PreUpdate
    protected void onUpdate() {
        validateCrossDomainReferences();
        this.updatedAt = Instant.now();
        syncCompletionTimestamp();
    }

    private void syncCompletionTimestamp() {
        if (this.status == TaskStatus.COMPLETED && this.completedAt == null) {
            this.completedAt = Instant.now();
        } else if (this.status != TaskStatus.COMPLETED) {
            this.completedAt = null;
        }
    }

    public void validateCrossDomainReferences() {
        if (domain == null) {
            throw new CrossDomainReferenceException("Task domain must be specified.");
        }
        if (parentTask != null && parentTask.getDomain() != null && parentTask.getDomain() != this.domain) {
            throw new CrossDomainReferenceException(
                "Subtask domain (" + this.domain + ") must match parent task domain (" + parentTask.getDomain() + ")."
            );
        }
        if (course != null && this.domain != DomainScope.ACADEMICS) {
            throw new CrossDomainReferenceException("A task referencing a course must have ACADEMICS domain.");
        }
        if (careerApplication != null && this.domain != DomainScope.CAREER) {
            throw new CrossDomainReferenceException("A task referencing a career application must have CAREER domain.");
        }
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public DomainScope getDomain() { return domain; }
    public void setDomain(DomainScope domain) { this.domain = domain; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getDueDate() { return dueDate; }
    public void setDueDate(Instant dueDate) { this.dueDate = dueDate; }

    public TaskPriority getPriority() { return priority; }
    public void setPriority(TaskPriority priority) { this.priority = priority; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) {
        this.status = status;
        syncCompletionTimestamp();
    }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Task getParentTask() { return parentTask; }
    public void setParentTask(Task parentTask) { this.parentTask = parentTask; }

    public List<Task> getSubtasks() { return subtasks; }
    public void setSubtasks(List<Task> subtasks) { this.subtasks = subtasks; }

    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }

    public CareerApplication getCareerApplication() { return careerApplication; }
    public void setCareerApplication(CareerApplication careerApplication) { this.careerApplication = careerApplication; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
