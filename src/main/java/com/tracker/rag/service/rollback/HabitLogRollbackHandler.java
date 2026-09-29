package com.tracker.rag.service.rollback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.domain.EntityRollbackException;
import com.tracker.rag.model.HabitLog;
import com.tracker.rag.repository.HabitLogRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class HabitLogRollbackHandler implements EntityRollbackHandler {

    private final HabitLogRepository repository;
    private final ObjectMapper objectMapper;

    public HabitLogRollbackHandler(HabitLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public EntityType getSupportedEntityType() {
        return EntityType.HABIT_LOG;
    }

    @Override
    public void deleteEntity(UUID entityId) {
        repository.deleteById(entityId);
    }

    @Override
    public void restoreEntity(UUID entityId, String jsonPayload) {
        try {
            HabitLog habitLog = objectMapper.readValue(jsonPayload, HabitLog.class);
            repository.save(habitLog);
        } catch (Exception e) {
            throw new EntityRollbackException("Failed to deserialize and restore HabitLog: " + entityId, e);
        }
    }
}
