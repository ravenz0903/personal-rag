package com.tracker.rag.contract;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventLogRecord(
        UUID id,
        DomainScope domain,
        EntityType entityType,
        UUID entityId,
        ActionType action,
        Instant timestamp,
        String payloadBefore,
        String payloadAfter,
        boolean undone,
        UUID undoneByEventId
) {
    public EventLogRecord {
        Objects.requireNonNull(id, "Event id cannot be null");
        Objects.requireNonNull(domain, "domain cannot be null");
        Objects.requireNonNull(entityType, "entityType cannot be null");
        Objects.requireNonNull(entityId, "entityId cannot be null");
        Objects.requireNonNull(action, "action cannot be null");
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    public EventLogRecord markUndone(UUID rollbackEventId) {
        if (this.undone) {
            throw new EventAlreadyRolledBackException(this.id, this.undoneByEventId);
        }
        return new EventLogRecord(
                id, domain, entityType, entityId, action, timestamp,
                payloadBefore, payloadAfter, true, rollbackEventId
        );
    }

    public static EventLogRecord create(
            DomainScope domain, EntityType type, UUID entityId, ActionType action,
            String before, String after
    ) {
        return new EventLogRecord(
                UUID.randomUUID(), domain, type, entityId, action, Instant.now(),
                before, after, false, null
        );
    }
}

