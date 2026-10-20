package com.tracker.rag.controller;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.model.TaskPriority;
import com.tracker.rag.model.Task;
import com.tracker.rag.repository.TaskRepository;
import com.tracker.rag.service.desktop.LocalReminderService;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"tracker.desktop.token=0123456789abcdef0123456789abcdef","spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("desktop")
@Transactional
class ReminderQueueIntegrationTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("tracker").toString().replace('\\','/')+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("tracker.documents.root",()->directory.resolve("documents").toString());
    }
    @Autowired TaskRepository tasks;
    @Autowired LocalReminderService reminders;
    @Autowired JdbcTemplate jdbc;

    private UUID seedQueue() {
        Instant start=Instant.now().minusSeconds(100).truncatedTo(ChronoUnit.MICROS);
        UUID last=null;
        for (int i=0;i<21;i++) {
            Task task=new Task(DomainScope.ACADEMICS,"Queued reminder "+i,TaskPriority.LOW,null);
            task.setRemindAt(start.plusSeconds(i));
            last=tasks.saveAndFlush(task).getId();
        }
        return last;
    }
    @Test void deliveredRemindersDoNotBlockLaterDueRecords() {
        UUID last=seedQueue();
        var first=reminders.claim(); assertEquals(20,first.size());
        for (var item:first) reminders.finish((UUID)item.get("taskId"),"task",(Instant)item.get("remindAt"),true);
        var next=reminders.claim(); assertEquals(1,next.size()); assertEquals(last,next.get(0).get("taskId"));
    }
    @Test void leasedRemindersDoNotBlockLaterDueRecords() {
        UUID last=seedQueue(); assertEquals(20,reminders.claim().size());
        var next=reminders.claim(); assertEquals(1,next.size()); assertEquals(last,next.get(0).get("taskId"));
        assertTrue(reminders.claim().isEmpty());
    }
    @Test void exhaustedRetriesDoNotBlockLaterDueRecords() {
        UUID last=seedQueue();
        for (int attempt=0;attempt<3;attempt++) {
            var batch=reminders.claim(); assertEquals(20,batch.size());
            for (var item:batch) reminders.finish((UUID)item.get("taskId"),"task",(Instant)item.get("remindAt"),false);
            jdbc.update("UPDATE desktop_reminder_attempts SET lease_until=NULL");
        }
        var next=reminders.claim(); assertEquals(1,next.size()); assertEquals(last,next.get(0).get("taskId"));
    }
}
