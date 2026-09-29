package com.tracker.rag.service.rollback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.domain.EntityRollbackException;
import com.tracker.rag.model.Habit;
import com.tracker.rag.repository.HabitRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class HabitRollbackHandler implements EntityRollbackHandler {

    private final HabitRepository repository;
    private final ObjectMapper objectMapper;

    public HabitRollbackHandler(HabitRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public EntityType getSupportedEntityType() {
        return EntityType.HABIT;
    }

    @Override
    public void deleteEntity(UUID entityId) {
        repository.deleteById(entityId);
    }

    @Override
    public void restoreEntity(UUID entityId, String jsonPayload) {
        try {
            Habit habit = objectMapper.readValue(jsonPayload, Habit.class);
            repository.save(habit);
        } catch (Exception e) {
            throw new EntityRollbackException("Failed to deserialize and restore Habit: " + entityId, e);
        }
    }
}
