package com.tracker.rag.model;

import com.tracker.rag.domain.ActionType;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.EntityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity representing an append-only event log entry for state tracking and undo.
 */
@Entity
@Table(
    name = "event_log",
    indexes = {
        @Index(name = "idx_event_log_domain_ts", columnList = "domain, timestamp DESC"),
        @Index(name = "idx_event_log_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_event_log_undone", columnList = "domain, undone, timestamp DESC")
    }
)
public class EventLogEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "domain", nullable = false, length = 32, updatable = false)
    private DomainScope domain;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 64, updatable = false)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32, updatable = false)
    private ActionType action;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    @Column(name = "payload_before", columnDefinition = "TEXT", updatable = false)
    private String payloadBefore;

    @Column(name = "payload_after", columnDefinition = "TEXT", updatable = false)
    private String payloadAfter;

    @Column(name = "diff_json", columnDefinition = "TEXT", updatable = false)
    private String diffJson;

    @Column(name = "metadata_json", columnDefinition = "TEXT", updatable = false)
    private String metadataJson;

    @Column(name = "undone", nullable = false)
    private boolean undone = false;

    @Column(name = "undone_by_event_id")
    private UUID undoneByEventId;

    public EventLogEntry() {
    }

    public EventLogEntry(
        UUID id,
        DomainScope domain,
        EntityType entityType,
        UUID entityId,
        ActionType action,
        Instant timestamp,
        String payloadBefore,
        String payloadAfter
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.domain = Objects.requireNonNull(domain, "DomainScope cannot be null");
        this.entityType = Objects.requireNonNull(entityType, "EntityType cannot be null");
        this.entityId = Objects.requireNonNull(entityId, "EntityId cannot be null");
        this.action = Objects.requireNonNull(action, "ActionType cannot be null");
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.payloadBefore = payloadBefore;
        this.payloadAfter = payloadAfter;
        this.undone = false;
        this.undoneByEventId = null;
    }

    public EventLogEntry(DomainScope domain, ActionType action, EntityType entityType, UUID entityId,
                         String payloadBefore, String payloadAfter, String diffJson, String metadataJson) {
        this.id = UUID.randomUUID();
        this.domain = Objects.requireNonNull(domain, "DomainScope cannot be null");
        this.action = Objects.requireNonNull(action, "ActionType cannot be null");
        this.entityType = Objects.requireNonNull(entityType, "EntityType cannot be null");
        this.entityId = Objects.requireNonNull(entityId, "EntityId cannot be null");
        this.timestamp = Instant.now();
        this.payloadBefore = payloadBefore;
        this.payloadAfter = payloadAfter;
        this.diffJson = diffJson;
        this.metadataJson = metadataJson;
        this.undone = false;
        this.undoneByEventId = null;
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.timestamp == null) {
            this.timestamp = Instant.now();
        }
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public DomainScope getDomain() { return domain; }
    public void setDomain(DomainScope domain) { this.domain = domain; }

    public EntityType getEntityType() { return entityType; }
    public void setEntityType(EntityType entityType) { this.entityType = entityType; }

    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }

    public ActionType getAction() { return action; }
    public void setAction(ActionType action) { this.action = action; }

    // Alias for eventType
    public ActionType getEventType() { return action; }
    public void setEventType(ActionType action) { this.action = action; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getPayloadBefore() { return payloadBefore; }
    public void setPayloadBefore(String payloadBefore) { this.payloadBefore = payloadBefore; }

    // Alias for beforeStateJson
    public String getBeforeStateJson() { return payloadBefore; }
    public void setBeforeStateJson(String beforeStateJson) { this.payloadBefore = beforeStateJson; }

    public String getPayloadAfter() { return payloadAfter; }
    public void setPayloadAfter(String payloadAfter) { this.payloadAfter = payloadAfter; }

    // Alias for afterStateJson
    public String getAfterStateJson() { return payloadAfter; }
    public void setAfterStateJson(String afterStateJson) { this.payloadAfter = afterStateJson; }

    public String getDiffJson() { return diffJson; }
    public void setDiffJson(String diffJson) { this.diffJson = diffJson; }

    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }

    public boolean isUndone() { return undone; }
    public void setUndone(boolean undone) { this.undone = undone; }

    public UUID getUndoneByEventId() { return undoneByEventId; }
    public void setUndoneByEventId(UUID undoneByEventId) { this.undoneByEventId = undoneByEventId; }

    public void markUndoneBy(UUID rollbackEventId) {
        this.undone = true;
        this.undoneByEventId = rollbackEventId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EventLogEntry that = (EventLogEntry) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "EventLogEntry{" +
            "id=" + id +
            ", domain=" + domain +
            ", entityType=" + entityType +
            ", entityId=" + entityId +
            ", action=" + action +
            ", timestamp=" + timestamp +
            ", undone=" + undone +
            ", undoneByEventId=" + undoneByEventId +
            '}';
    }
}
