package com.tracker.rag.service.rollback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.domain.EntityRollbackException;
import com.tracker.rag.model.Task;
import com.tracker.rag.repository.TaskRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TaskRollbackHandler implements EntityRollbackHandler {

    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    public TaskRollbackHandler(TaskRepository taskRepository, ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public EntityType getSupportedEntityType() {
        return EntityType.TASK;
    }

    @Override
    public void deleteEntity(UUID entityId) {
        taskRepository.deleteById(entityId);
    }

    @Override
    public void restoreEntity(UUID entityId, String jsonPayload) {
        try {
            Task task = objectMapper.readValue(jsonPayload, Task.class);
            taskRepository.save(task);
        } catch (Exception e) {
            throw new EntityRollbackException("Failed to deserialize and restore Task: " + entityId, e);
        }
    }
}
