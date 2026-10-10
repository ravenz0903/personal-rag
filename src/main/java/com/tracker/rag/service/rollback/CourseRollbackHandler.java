package com.tracker.rag.service.rollback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.domain.EntityRollbackException;
import com.tracker.rag.model.Course;
import com.tracker.rag.repository.CourseRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CourseRollbackHandler implements EntityRollbackHandler {

    private final CourseRepository courseRepository;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tracker.rag.service.desktop.DesktopUndoGuard desktop;

    public CourseRollbackHandler(CourseRepository courseRepository, ObjectMapper objectMapper) {
        this.courseRepository = courseRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public EntityType getSupportedEntityType() {
        return EntityType.COURSE;
    }

    @Override
    public void deleteEntity(UUID entityId) {
        courseRepository.deleteById(entityId);
    }

    @Override
    public void restoreEntity(UUID entityId, String jsonPayload) {
        try {
            Course course = desktop == null ? objectMapper.readValue(jsonPayload, Course.class) : objectMapper.readerForUpdating(courseRepository.findById(entityId).orElseThrow()).readValue(jsonPayload);
            courseRepository.save(course);
        } catch (Exception e) {
            throw new EntityRollbackException("Failed to deserialize and restore Course: " + entityId, e);
        }
    }
}
