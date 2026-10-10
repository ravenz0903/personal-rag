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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
    name = "habits",
    indexes = {
        @Index(name = "idx_habits_domain", columnList = "domain")
    }
)
public class Habit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "domain", nullable = false, length = 32)
    private DomainScope domain = DomainScope.PERSONAL;

    @NotBlank
    @Size(min = 1, max = 255)
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 32)
    private HabitFrequency frequency = HabitFrequency.DAILY;

    @Column(name = "custom_days_mask")
    private Integer customDaysMask = 127; // Mon-Sun default (1111111 binary)

    @Min(1)
    @Column(name = "target_count_per_day", nullable = false)
    private int targetCountPerDay = 1;

    @NotBlank
    @Size(min = 1, max = 64)
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(name = "archived", nullable = false)
    private boolean archived = false;

    @OneToMany(mappedBy = "habit", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<HabitLog> logs = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Habit() {
        this.domain = DomainScope.PERSONAL;
    }

    public Habit(String name, HabitFrequency frequency, int targetCountPerDay, String timezone) {
        this.domain = DomainScope.PERSONAL;
        this.name = name;
        this.frequency = frequency != null ? frequency : HabitFrequency.DAILY;
        this.targetCountPerDay = targetCountPerDay > 0 ? targetCountPerDay : 1;
        this.timezone = timezone != null ? timezone : "UTC";
    }

    @PrePersist
    protected void onCreate() {
        validateTimezone();
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        validateTimezone();
        this.updatedAt = Instant.now();
    }

    public void validateTimezone() {
        if (timezone == null || timezone.trim().isEmpty()) {
            throw new IllegalArgumentException("Timezone cannot be null or blank.");
        }
        try {
            ZoneId.of(timezone.trim());
        } catch (ZoneRulesException ex) {
            throw new IllegalArgumentException("Invalid IANA timezone: " + timezone, ex);
        }
    }

    public ZoneId getZoneId() {
        return ZoneId.of(timezone.trim());
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public DomainScope getDomain() { return domain; }
    public void setDomain(DomainScope domain) { this.domain = domain; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public HabitFrequency getFrequency() { return frequency; }
    public void setFrequency(HabitFrequency frequency) { this.frequency = frequency; }

    public Integer getCustomDaysMask() { return customDaysMask; }
    public void setCustomDaysMask(Integer customDaysMask) { this.customDaysMask = customDaysMask; }

    public int getTargetCountPerDay() { return targetCountPerDay; }
    public void setTargetCountPerDay(int targetCountPerDay) { this.targetCountPerDay = targetCountPerDay; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }

    public List<HabitLog> getLogs() { return logs; }
    public void setLogs(List<HabitLog> logs) { this.logs = logs; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

}
