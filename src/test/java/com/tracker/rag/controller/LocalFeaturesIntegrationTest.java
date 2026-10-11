package com.tracker.rag.controller;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tracker.rag.domain.*;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.*;
import com.tracker.rag.service.desktop.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"tracker.desktop.token=0123456789abcdef0123456789abcdef","spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("desktop")
class LocalFeaturesIntegrationTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("tracker").toString().replace('\\','/')+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");registry.add("tracker.documents.root",()->directory.resolve("documents").toString());}
    @Autowired LocalDataService data;@Autowired DesktopTaskService service;@Autowired TaskRepository tasks;@Autowired LocalReminderService reminders;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate jdbc;
    @Autowired com.tracker.rag.service.RollbackService rollback;
    @Autowired com.tracker.rag.service.EventLogService history;
    @Autowired com.tracker.rag.service.CaptureService capture;
    @Autowired LocalRecordService records;
    @Autowired HabitRepository habits;@Autowired HabitLogRepository logs;
    JsonNode json(String value)throws Exception{return mapper.readTree(value);}
    @Test void versionedSchemaValidatesAndSettingsPersist()throws Exception{
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND type='SQL'",Integer.class));
        data.preferences(json("{\"displayName\":\"Harsh\",\"timezone\":\"America/New_York\",\"weekStart\":1,\"remindersEnabled\":true}"));assertEquals("America/New_York",data.zone().getId());
    }
    @Test void noteConflictsAndPersonalSearchAreEnforced()throws Exception{
        var input=json("{\"domain\":\"PERSONAL\",\"title\":\"Private needle\",\"content\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Study notes\"}]}]},\"tags\":[\"study\"]}");
        var saved=data.saveNote(null,input);java.util.UUID id=(java.util.UUID)saved.get("id");
        assertEquals(0L,((Number)data.search("Private needle","ALL",0).get("total")).longValue());assertEquals(1L,((Number)data.search("Private needle","PERSONAL",0).get("total")).longValue());
        assertThrows(ResponseStatusException.class,()->data.note(id,"ACADEMICS"));
        ObjectNode update=(ObjectNode)input.deepCopy();update.put("revision",0);data.saveNote(id,update);assertThrows(ResponseStatusException.class,()->data.saveNote(id,update));
        assertThrows(IllegalArgumentException.class,()->data.saveNote(null,json("{\"domain\":\"ACADEMICS\",\"title\":\"Unsafe\",\"content\":{\"type\":\"doc\",\"content\":[{\"type\":\"text\",\"text\":\"bad\",\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"javascript:alert(1)\"}}]}]}}")));
    }
    @Test void calendarAllDayDatesAndTimedRangesAreValidated()throws Exception{
        var event=data.saveEvent(null,json("{\"domain\":\"ACADEMICS\",\"title\":\"Leap day\",\"allDay\":true,\"startDate\":\"2028-02-29\",\"endDate\":\"2028-03-01\"}"));assertEquals("2028-02-29",event.get("startDate"));assertNull(event.get("startAt"));
        assertThrows(IllegalArgumentException.class,()->data.saveEvent(null,json("{\"domain\":\"ACADEMICS\",\"title\":\"Invalid\",\"allDay\":false,\"startAt\":\"2026-10-11T12:00:00Z\",\"endAt\":\"2026-10-11T11:00:00Z\"}")));
    }
    @Test void duplicateCompletionDoesNotCreateTwoSuccessors(){
        Task task=new Task(DomainScope.ACADEMICS,"Recurring",TaskPriority.MEDIUM,Instant.now().minusSeconds(86400));task.setRecurrence("DAILY");task=tasks.saveAndFlush(task);
        var completed=service.change(task.getId(),task.getDomain(),task.getVersion(),"COMPLETED",null,null,false);assertNotNull(completed.getSuccessorId());assertTrue(tasks.findById(completed.getSuccessorId()).orElseThrow().getDueDate().isAfter(Instant.now()));
        Task original=task;assertThrows(ResponseStatusException.class,()->service.change(original.getId(),original.getDomain(),original.getVersion(),"COMPLETED",null,null,false));
        var again=service.change(completed.getId(),completed.getDomain(),completed.getVersion(),"COMPLETED",null,null,false);assertEquals(completed.getSuccessorId(),again.getSuccessorId());
    }
    @Test void claimsAreLeasedBoundedAndArchiveCancelsReminder(){
        Task task=new Task(DomainScope.PERSONAL,"Reminder",TaskPriority.LOW,Instant.now().plusSeconds(60));task.setRemindAt(Instant.now().minusSeconds(2));task=tasks.saveAndFlush(task);var claimed=reminders.claim();assertTrue(claimed.stream().anyMatch(r->r.get("taskId").equals(taskId())));assertTrue(reminders.claim().isEmpty());
        task.setArchived(true);tasks.saveAndFlush(task);jdbc.update("UPDATE desktop_reminder_attempts SET lease_until=NULL");assertTrue(reminders.claim().isEmpty());
    }
    private java.util.UUID taskId(){return tasks.findByDomain(DomainScope.PERSONAL).stream().filter(t->t.getTitle().equals("Reminder")).findFirst().orElseThrow().getId();}
    @Test void undoCompletionRemovesOnlyUneditedSuccessor(){
        Task task=new Task(DomainScope.CAREER,"Safe undo",TaskPriority.MEDIUM,Instant.now().minusSeconds(60));task.setRecurrence("DAILY");task=tasks.saveAndFlush(task);Task completed=service.change(task.getId(),task.getDomain(),task.getVersion(),"COMPLETED",null,null,false);
        var event=history.getRecentEventsByDomain(DomainScope.CAREER,100).stream().filter(e->e.getEntityId().equals(completed.getId())).findFirst().orElseThrow();rollback.rollbackEvent(event.getId());assertTrue(tasks.findById(completed.getSuccessorId()).isEmpty());assertEquals(TaskStatus.PENDING,tasks.findById(completed.getId()).orElseThrow().getStatus());
    }
    @Test void undoCompletionRejectsEditedSuccessor(){
        Task task=new Task(DomainScope.CAREER,"Edited successor",TaskPriority.MEDIUM,Instant.now().minusSeconds(60));task.setRecurrence("DAILY");task=tasks.saveAndFlush(task);Task completed=service.change(task.getId(),task.getDomain(),task.getVersion(),"COMPLETED",null,null,false);
        var event=history.getRecentEventsByDomain(DomainScope.CAREER,100).stream().filter(e->e.getEntityId().equals(completed.getId())).findFirst().orElseThrow();Task next=tasks.findById(completed.getSuccessorId()).orElseThrow();service.change(next.getId(),next.getDomain(),next.getVersion(),"IN_PROGRESS",null,null,false);assertThrows(ResponseStatusException.class,()->rollback.rollbackEvent(event.getId()));assertTrue(tasks.findById(next.getId()).isPresent());
    }
    @Test void subtaskCannotCrossAreasOrNest(){
        Task parent=tasks.saveAndFlush(new Task(DomainScope.ACADEMICS,"Parent",TaskPriority.LOW,null));Task child=new Task(DomainScope.ACADEMICS,"Child",TaskPriority.LOW,null);service.details(child,java.util.Map.of("parentId",parent.getId().toString()),TaskStatus.PENDING);child=tasks.saveAndFlush(child);
        Task nested=new Task(DomainScope.ACADEMICS,"Nested",TaskPriority.LOW,null);String childId=child.getId().toString();assertThrows(IllegalArgumentException.class,()->service.details(nested,java.util.Map.of("parentId",childId),TaskStatus.PENDING));Task wrong=new Task(DomainScope.PERSONAL,"Wrong area",TaskPriority.LOW,null);assertThrows(IllegalArgumentException.class,()->service.details(wrong,java.util.Map.of("parentId",parent.getId().toString()),TaskStatus.PENDING));
    }
    @Test void archivedHabitAndUndoPreserveItsLogs(){
        Habit habit=habits.saveAndFlush(new Habit("Preserve logs",HabitFrequency.DAILY,1,"Asia/Kolkata"));logs.saveAndFlush(new HabitLog(habit,LocalDate.now(habit.getZoneId()),Instant.now(),1,null));
        var row=records.records("habit").stream().filter(h->h.get("id").equals(habit.getId())).findFirst().orElseThrow();var input=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(row);input.put("archived",true);records.update("habit",habit.getId(),input);
        var event=history.getRecentEventsByDomain(DomainScope.PERSONAL,100).stream().filter(e->e.getEntityId().equals(habit.getId())).findFirst().orElseThrow();rollback.rollbackEvent(event.getId());assertFalse(habits.findById(habit.getId()).orElseThrow().isArchived());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM habit_logs WHERE habit_id=?",Integer.class,habit.getId()));
    }
    @Test void staleEditCannotEvenCreateAnApprovalPreview(){
        Task task=tasks.saveAndFlush(new Task(DomainScope.ACADEMICS,"Stale form",TaskPriority.MEDIUM,null));long old=task.getVersion();service.change(task.getId(),task.getDomain(),old,"IN_PROGRESS",null,null,false);
        assertThrows(ResponseStatusException.class,()->capture.createStructuredPreview(DomainScope.ACADEMICS,"TASK_UPDATE",java.util.Map.of("taskId",task.getId().toString(),"expectedVersion",old,"title","Stale overwrite","priority","LOW")));
    }
    @Test void reminderRetriesStopAtThreeAndEditedReminderIsIneligible(){
        Task task=new Task(DomainScope.ACADEMICS,"Retry bounded",TaskPriority.LOW,null);task.setRemindAt(Instant.now().minusSeconds(3).truncatedTo(java.time.temporal.ChronoUnit.MICROS));task=tasks.saveAndFlush(task);var id=task.getId();var at=task.getRemindAt();
        for(int i=0;i<3;i++){assertTrue(reminders.claim().stream().anyMatch(r->r.get("taskId").equals(id)));reminders.finish(id,"task",at,false);jdbc.update("UPDATE desktop_reminder_attempts SET lease_until=NULL WHERE record_id=?",id);}assertFalse(reminders.claim().stream().anyMatch(r->r.get("taskId").equals(id)));assertTrue(reminders.eligible(id,"task",at));task.setArchived(true);tasks.saveAndFlush(task);assertFalse(reminders.eligible(id,"task",at));
    }
    @Test void recurrenceClampsAndSkipsMissedDates(){
        ZoneId zone=ZoneId.of("America/New_York");assertEquals(LocalDate.of(2026,2,28),DesktopTaskService.nextDue(LocalDate.of(2026,1,31).atTime(9,0).atZone(zone).toInstant(),"MONTHLY",31,zone,LocalDate.of(2026,2,2).atStartOfDay(zone).toInstant()).atZone(zone).toLocalDate());
        var friday=LocalDate.of(2026,10,9).atTime(9,0).atZone(zone).toInstant();assertEquals(DayOfWeek.MONDAY,DesktopTaskService.nextDue(friday,"WEEKDAYS",9,zone,friday).atZone(zone).getDayOfWeek());
    }
    @Test void recurringClockReturnsToItsAnchorAfterDstGap(){
        ZoneId zone=ZoneId.of("America/New_York");Instant first=LocalDate.of(2026,3,7).atTime(2,30).atZone(zone).toInstant();Instant gap=DesktopTaskService.nextDue(first,"DAILY",7,"02:30",zone,first);assertEquals(LocalTime.of(3,30),gap.atZone(zone).toLocalTime());Instant after=DesktopTaskService.nextDue(gap,"DAILY",7,"02:30",zone,gap);assertEquals(LocalTime.of(2,30),after.atZone(zone).toLocalTime());assertEquals(LocalDate.of(2026,3,9),after.atZone(zone).toLocalDate());
    }
}
