package com.tracker.rag.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.*;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.TaskRepository;
import com.tracker.rag.service.CaptureService;
import com.tracker.rag.service.ai.AiClient;
import com.tracker.rag.service.desktop.LocalDataService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"ai.provider=test-local", "tracker.desktop.token=0123456789abcdef0123456789abcdef"})
@ActiveProfiles("desktop") @Import(LocalAssistantActionsTest.Config.class)
class LocalAssistantActionsTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("tracker").toString().replace('\\','/')+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("tracker.documents.root",()->directory.resolve("documents").toString());}
    @TestConfiguration static class Config { @Bean @Primary FakeAi localFake(){return new FakeAi();} }
    static class FakeAi implements AiClient {
        String output;int calls;
        public float[] generateEmbedding(String text,DomainScope domain){throw new AssertionError("No embeddings should be generated for actions");}
        public String generateChat(String system,String input){throw new AssertionError("Use the JSON action contract");}
        public String generateJson(String system,String input){assertTrue(system.contains("remindLocal"));calls++;return output;}
        public void streamChat(String system,String input,Consumer<String> chunks,Runnable complete){throw new AssertionError();}
    }
    @Autowired FakeAi ai;@Autowired LocalAssistantActionController assistant;@Autowired TaskRepository tasks;
    @Autowired CaptureService capture;@Autowired LocalDataService data;@Autowired ObjectMapper mapper;
    @BeforeEach void reset(){ai.calls=0;}
    LocalAssistantActionController.Request request(String area,String input,Task selected){return new LocalAssistantActionController.Request(area,input,selected==null?null:selected.getId(),selected==null?null:selected.getVersion(),UUID.randomUUID());}
    @Test void reminderUsesActualReminderFieldAndOnlyWritesAfterApproval() throws Exception {
        ai.output="{\"action\":\"create_task\",\"area\":\"ACADEMICS\",\"title\":\"Review notes\",\"remindLocal\":\"2039-04-03T09:30\"}";
        long before=tasks.count();var draft=assistant.draft(request("ACADEMICS","Remind me on 2039-04-03 at 09:30 to Review notes",null));
        assertEquals("proposal",draft.type());assertEquals(before,tasks.count());
        UUID id=(UUID)capture.commitConfirmation(draft.preview().confirmationToken());Task saved=tasks.findById(id).orElseThrow();
        assertNull(saved.getDueDate());assertEquals(LocalDateTime.parse("2039-04-03T09:30").atZone(data.zone()).toInstant(),saved.getRemindAt());
    }
    @Test void guessedClockAndDeadlineOnlyReminderAreClarifiedWithoutSaving() throws Exception {
        long before=tasks.count();
        ai.output="{\"action\":\"create_event\",\"area\":\"CAREER\",\"title\":\"Catch up\",\"startLocal\":\"2039-04-03T15:00\",\"endLocal\":\"2039-04-03T17:00\"}";
        assertEquals("clarify",assistant.draft(request("CAREER","Add Catch up on 2039-04-03 afternoon",null)).type());
        ai.output="{\"action\":\"create_task\",\"area\":\"ACADEMICS\",\"title\":\"Review\",\"dueLocal\":\"2039-04-03T09:30\"}";
        assertEquals("clarify",assistant.draft(request("ACADEMICS","Remind me on 2039-04-03 at 09:30 to Review",null)).type());assertEquals(before,tasks.count());
    }
    @Test void editsRequireExplicitSelectionAndApprovalRetainsRevisionCheck() throws Exception {
        Task task=tasks.saveAndFlush(new Task(DomainScope.ACADEMICS,"Chosen task",TaskPriority.LOW,null));
        ai.output=mapper.writeValueAsString(Map.of("action","edit_task","recordId",task.getId().toString(),"priority","HIGH"));
        var unresolved=assistant.draft(request("ACADEMICS","Change Chosen task priority to high",null));
        assertEquals("clarify",unresolved.type());assertTrue(unresolved.choices().stream().anyMatch(c->c.id().equals(task.getId())));
        var resolved=assistant.draft(request("ACADEMICS","Change Chosen task priority to high",task));assertEquals("proposal",resolved.type());
        task.setTitle("Changed after preview");tasks.saveAndFlush(task);
        assertThrows(RuntimeException.class,()->capture.commitConfirmation(resolved.preview().confirmationToken()));
        assertEquals(TaskPriority.LOW,tasks.findById(task.getId()).orElseThrow().getPriority());capture.cancelPreviewCard(resolved.preview().confirmationToken());
    }
    @Test void combinedScopeCannotSelectOrCreatePersonalRecords() throws Exception {
        Task secret=tasks.saveAndFlush(new Task(DomainScope.PERSONAL,"Private title",TaskPriority.MEDIUM,null));
        assertThrows(IllegalArgumentException.class,()->assistant.draft(request("ALL","Change the selected task",secret)));assertEquals(0,ai.calls);
        ai.output="{\"action\":\"create_task\",\"area\":\"PERSONAL\",\"title\":\"Bad scope\"}";
        assertThrows(IllegalArgumentException.class,()->assistant.draft(request("ALL","Add a task called Bad scope",null)));
        assertTrue(tasks.findByDomain(DomainScope.PERSONAL).stream().noneMatch(t->t.getTitle().equals("Bad scope")));
    }
    @Test void reviewedStandaloneEventUsesCalendarRecords() throws Exception {
        ai.output="{\"action\":\"create_event\",\"area\":\"CAREER\",\"title\":\"Reviewed call\",\"startLocal\":\"2039-04-03T09:30\",\"endLocal\":\"2039-04-03T10:10\"}";
        int before=data.events("CAREER",false).size();var draft=assistant.draft(request("CAREER","Career event Reviewed call on 2039-04-03 from 09:30 to 10:10",null));
        assertEquals("EVENT",draft.preview().entityType());assertEquals(before,data.events("CAREER",false).size());
        UUID id=(UUID)capture.commitConfirmation(draft.preview().confirmationToken());assertTrue(data.events("CAREER",false).stream().anyMatch(e->id.equals(e.get("id"))));
    }
}
