package com.tracker.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.ActionType;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.domain.UnscopedQueryException;
import com.tracker.rag.model.EventLogEntry;
import com.tracker.rag.repository.EventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class EventLogService {

    private static final Logger log = LoggerFactory.getLogger(EventLogService.class);

    private final EventLogRepository eventLogRepository;
    private final ObjectMapper objectMapper;

    public EventLogService(EventLogRepository eventLogRepository, ObjectMapper objectMapper) {
        this.eventLogRepository = eventLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Record a mutation with raw Object before/after states.
     * Serializes states to JSON strings using the configured ObjectMapper.
     */
    @Transactional
    public EventLogEntry recordMutation(
        DomainScope domain,
        EntityType entityType,
        UUID entityId,
        ActionType action,
        Object entityBefore,
        Object entityAfter
    ) {
        String payloadBefore = serializeToJson(entityBefore);
        String payloadAfter = serializeToJson(entityAfter);

        return recordEvent(domain, entityType, entityId, action, payloadBefore, payloadAfter);
    }

    private static final java.util.concurrent.atomic.AtomicLong LAST_TIMESTAMP_NANOS = new java.util.concurrent.atomic.AtomicLong(0);

    public static Instant getMonotonicInstant() {
        long currentNanos = System.currentTimeMillis() * 1_000_000L;
        long lastNanos = LAST_TIMESTAMP_NANOS.get();
        long nextNanos = Math.max(currentNanos, lastNanos + 1_000L);
        while (!LAST_TIMESTAMP_NANOS.compareAndSet(lastNanos, nextNanos)) {
            lastNanos = LAST_TIMESTAMP_NANOS.get();
            nextNanos = Math.max(currentNanos, lastNanos + 1_000L);
        }
        return Instant.ofEpochSecond(nextNanos / 1_000_000_000L, nextNanos % 1_000_000_000L);
    }

    /**
     * Record an event entry with explicit JSON payloads.
     */
    @Transactional
    public EventLogEntry recordEvent(
        DomainScope domain,
        EntityType entityType,
        UUID entityId,
        ActionType action,
        String payloadBefore,
        String payloadAfter
    ) {
        if (domain == null) {
            throw new UnscopedQueryException("Domain scope must be explicitly specified (fail-closed)");
        }
        Objects.requireNonNull(entityType, "EntityType cannot be null");
        Objects.requireNonNull(entityId, "EntityId cannot be null");
        Objects.requireNonNull(action, "ActionType cannot be null");

        EventLogEntry entry = new EventLogEntry(
            UUID.randomUUID(),
            domain,
            entityType,
            entityId,
            action,
            getMonotonicInstant(),
            payloadBefore,
            payloadAfter
        );

        EventLogEntry saved = eventLogRepository.save(entry);
        log.debug("Recorded event log entry: id={}, action={}, entityType={}, entityId={}", 
            saved.getId(), action, entityType, entityId);
        return saved;
    }

    /**
     * Query recent event log entries for a single domain (fail-closed if domain is null).
     */
    @Transactional(readOnly = true)
    public List<EventLogEntry> getRecentEventsByDomain(DomainScope domain, int limit) {
        if (domain == null) {
            throw new UnscopedQueryException("Domain scope must be explicitly specified (fail-closed)");
        }
        int maxResults = Math.max(1, Math.min(limit, 100));
        return eventLogRepository.findByDomainOrderByTimestampDesc(domain, PageRequest.of(0, maxResults));
    }

    /**
     * Query recent event log entries across multiple domains.
     * Enforces fail-closed validation on empty sets.
     */
    @Transactional(readOnly = true)
    public List<EventLogEntry> getRecentEventsByDomains(Set<DomainScope> domains, int limit) {
        Set<DomainScope> validatedScopes = DomainScope.validateAndResolve(domains);
        int maxResults = Math.max(1, Math.min(limit, 100));
        return eventLogRepository.findByDomainInOrderByTimestampDesc(validatedScopes, PageRequest.of(0, maxResults));
    }

    /**
     * Query full audit history for a specific entity.
     */
    @Transactional(readOnly = true)
    public List<EventLogEntry> getEntityHistory(EntityType entityType, UUID entityId) {
        Objects.requireNonNull(entityType, "EntityType cannot be null");
        Objects.requireNonNull(entityId, "EntityId cannot be null");
        return eventLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDesc(entityType, entityId);
    }

    private String serializeToJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON for event log", e);
            throw new RuntimeException("JSON serialization failed for audit logging", e);
        }
    }
}
