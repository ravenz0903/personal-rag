package com.tracker.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.config.JacksonEventLogConfig;
import com.tracker.rag.domain.ActionType;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.domain.UnscopedQueryException;
import com.tracker.rag.model.EventLogEntry;
import com.tracker.rag.repository.EventLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventLogServiceTest {

    @Mock
    private EventLogRepository eventLogRepository;

    private ObjectMapper objectMapper;
    private EventLogService eventLogService;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonEventLogConfig().eventLogObjectMapper();
        eventLogService = new EventLogService(eventLogRepository, objectMapper);
    }

    @Test
    @DisplayName("recordMutation: Serializes before and after objects and saves entry")
    void testRecordMutation() {
        UUID entityId = UUID.randomUUID();
        Map<String, Object> before = Map.of("title", "Old Title");
        Map<String, Object> after = Map.of("title", "New Title");

        when(eventLogRepository.save(any(EventLogEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EventLogEntry saved = eventLogService.recordMutation(
                DomainScope.ACADEMICS,
                EntityType.TASK,
                entityId,
                ActionType.UPDATE,
                before,
                after
        );

        assertThat(saved.getDomain()).isEqualTo(DomainScope.ACADEMICS);
        assertThat(saved.getEntityType()).isEqualTo(EntityType.TASK);
        assertThat(saved.getEntityId()).isEqualTo(entityId);
        assertThat(saved.getAction()).isEqualTo(ActionType.UPDATE);
        assertThat(saved.getPayloadBefore()).contains("Old Title");
        assertThat(saved.getPayloadAfter()).contains("New Title");
        assertThat(saved.isUndone()).isFalse();

        ArgumentCaptor<EventLogEntry> captor = ArgumentCaptor.forClass(EventLogEntry.class);
        verify(eventLogRepository).save(captor.capture());
        assertThat(captor.getValue().getEntityId()).isEqualTo(entityId);
    }

    @Test
    @DisplayName("recordEvent: Fail-closed validation throws on null domain")
    void testRecordEventNullDomainThrows() {
        assertThatThrownBy(() -> eventLogService.recordEvent(
                null, EntityType.TASK, UUID.randomUUID(), ActionType.CREATE, null, "{}"
        )).isInstanceOf(UnscopedQueryException.class);
    }

    @Test
    @DisplayName("getRecentEventsByDomain: Enforces fail-closed validation on null domain")
    void testGetRecentEventsNullDomainThrows() {
        assertThatThrownBy(() -> eventLogService.getRecentEventsByDomain(null, 10))
                .isInstanceOf(UnscopedQueryException.class);
    }

    @Test
    @DisplayName("getRecentEventsByDomains: Enforces fail-closed validation on empty sets")
    void testGetRecentEventsEmptyDomainsThrows() {
        assertThatThrownBy(() -> eventLogService.getRecentEventsByDomains(Collections.emptySet(), 10))
                .isInstanceOf(UnscopedQueryException.class);
    }

    @Test
    @DisplayName("getRecentEventsByDomain: queries repository with limit")
    void testGetRecentEvents() {
        when(eventLogRepository.findByDomainOrderByTimestampDesc(eq(DomainScope.CAREER), any(Pageable.class)))
                .thenReturn(List.of());

        List<EventLogEntry> result = eventLogService.getRecentEventsByDomain(DomainScope.CAREER, 20);
        assertThat(result).isEmpty();
        verify(eventLogRepository).findByDomainOrderByTimestampDesc(eq(DomainScope.CAREER), any(Pageable.class));
    }
}
