package com.tracker.rag.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class JacksonEventLogConfig {

    @Bean
    @Primary
    public ObjectMapper eventLogObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.addMixIn(com.tracker.rag.model.Course.class, CourseJson.class);
        mapper.addMixIn(com.tracker.rag.model.Habit.class, HabitJson.class);
        mapper.addMixIn(com.tracker.rag.model.Task.class, TaskJson.class);
        mapper.addMixIn(com.tracker.rag.model.AssessmentCategory.class, CategoryJson.class);
        return mapper;
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"categories", "assessments", "hibernateLazyInitializer", "handler"})
    private abstract static class CourseJson {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"logs", "zoneId", "hibernateLazyInitializer", "handler"})
    private abstract static class HabitJson {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"subtasks", "parentTask", "hibernateLazyInitializer", "handler"})
    private abstract static class TaskJson {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"assessments", "hibernateLazyInitializer", "handler"})
    private abstract static class CategoryJson {}
}
