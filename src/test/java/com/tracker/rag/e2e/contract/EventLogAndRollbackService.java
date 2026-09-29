package com.tracker.rag.e2e.contract;

import java.util.List;
import java.util.UUID;

public interface EventLogAndRollbackService {
    EventLogRecord recordMutation(
            DomainScope domain,
            EntityType entityType,
            UUID entityId,
            ActionType action,
            String payloadBefore,
            String payloadAfter
    );

    List<EventLogRecord> getRecentEvents(DomainScope domain, int limit);

    EventLogRecord rollbackLastMutation(DomainScope domain);

    EventLogRecord rollbackEvent(UUID eventId);
}
