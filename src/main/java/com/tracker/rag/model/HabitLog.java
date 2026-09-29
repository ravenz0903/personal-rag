package com.tracker.rag.model;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "habit_logs",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_habit_log_date", columnNames = {"habit_id", "log_date"})
    },
    indexes = {
        @Index(name = "idx_habit_logs_date", columnList = "habit_id, log_date")
    }
)
public class HabitLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "habit_id", nullable = false)
    private Habit habit;

    @NotNull
    @Column(name = "log_date", nullable = false)
    private LocalDate logDate;

    @NotNull
    @Column(name = "logged_at", nullable = false)
    private Instant loggedAt;

    @Min(1)
    @Column(name = "units_completed", nullable = false)
    private int unitsCompleted = 1;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    public HabitLog() {
    }

    public HabitLog(Habit habit, LocalDate logDate, Instant loggedAt, int unitsCompleted, String notes) {
        this.habit = habit;
        this.logDate = logDate;
        this.loggedAt = loggedAt != null ? loggedAt : Instant.now();
        this.unitsCompleted = unitsCompleted > 0 ? unitsCompleted : 1;
        this.notes = notes;
    }

    @PrePersist
    protected void onCreate() {
        if (this.loggedAt == null) {
            this.loggedAt = Instant.now();
        }
        validateNotFutureDate();
    }

    public void validateNotFutureDate() {
        if (habit != null && logDate != null) {
            LocalDate todayInHabitZone = ZonedDateTime.now(habit.getZoneId()).toLocalDate();
            if (logDate.isAfter(todayInHabitZone)) {
                throw new IllegalArgumentException("Cannot log habit for a future date: " + logDate + " (today is " + todayInHabitZone + ")");
            }
        }
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Habit getHabit() { return habit; }
    public void setHabit(Habit habit) { this.habit = habit; }

    public LocalDate getLogDate() { return logDate; }
    public void setLogDate(LocalDate logDate) { this.logDate = logDate; }

    public Instant getLoggedAt() { return loggedAt; }
    public void setLoggedAt(Instant loggedAt) { this.loggedAt = loggedAt; }

    public int getUnitsCompleted() { return unitsCompleted; }
    public void setUnitsCompleted(int unitsCompleted) { this.unitsCompleted = unitsCompleted; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
