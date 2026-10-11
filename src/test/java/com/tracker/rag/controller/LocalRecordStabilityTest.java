package com.tracker.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tracker.rag.domain.*;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.*;
import com.tracker.rag.service.*;
import com.tracker.rag.service.desktop.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"tracker.desktop.token=0123456789abcdef0123456789abcdef"})
@ActiveProfiles("desktop")
class LocalRecordStabilityTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("tracker").toString().replace('\\','/')+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");registry.add("tracker.documents.root",()->directory.resolve("documents").toString());}
    @Autowired LocalRecordService records;@Autowired LocalDataService data;@Autowired ObjectMapper mapper;
    @Autowired CourseRepository courses;@Autowired AssessmentCategoryRepository categories;@Autowired AssessmentRepository assessments;
    @Autowired HabitRepository habits;@Autowired CareerApplicationRepository applications;
    @Autowired RollbackService rollback;@Autowired EventLogService history;
    @Autowired CaptureService capture;
    ObjectNode record(String kind,UUID id){return mapper.valueToTree(records.records(kind).stream().filter(r->id.equals(r.get("id"))).findFirst().orElseThrow());}
    @Test void staleCourseHabitAndApplicationFormsCannotOverwrite() {
        Course course=courses.saveAndFlush(new Course("CONFLICT101","Original course","Term",BigDecimal.ONE,new BigDecimal("80")));
        Habit habit=habits.saveAndFlush(new Habit("Original habit",HabitFrequency.DAILY,1,"Asia/Kolkata"));
        CareerApplication application=applications.saveAndFlush(new CareerApplication("Original company","Role",CareerStatus.WISHLIST,null));
        for(var item:Map.of("course",course.getId(),"habit",habit.getId(),"application",application.getId()).entrySet()){
            String field=item.getKey().equals("application")?"company":"name";
            ObjectNode stale=record(item.getKey(),item.getValue()),fresh=stale.deepCopy();fresh.put(field,"Newer value");records.update(item.getKey(),item.getValue(),fresh);
            stale.put(field,"Stale overwrite");assertThrows(ResponseStatusException.class,()->records.update(item.getKey(),item.getValue(),stale));
            assertEquals("Newer value",record(item.getKey(),item.getValue()).path(field).asText());
        }
    }
    @Test void courseUndoRetainsAssessmentsAndRejectsOlderChanges(){
        Course course=courses.saveAndFlush(new Course("UNDO101","Course before","Term",BigDecimal.ONE,new BigDecimal("75")));
        AssessmentCategory category=categories.saveAndFlush(new AssessmentCategory(course,"Exam",new BigDecimal("100"),0));
        Assessment assessment=assessments.saveAndFlush(new Assessment(course,category,"Retained exam",new BigDecimal("100")));
        ObjectNode edit=record("course",course.getId());edit.put("name","Course after");records.update("course",course.getId(),edit);
        var first=history.getRecentEventsByDomain(DomainScope.ACADEMICS,100).stream().filter(e->course.getId().equals(e.getEntityId())).findFirst().orElseThrow();
        ObjectNode next=record("course",course.getId());next.put("name","Course latest");records.update("course",course.getId(),next);
        assertThrows(ResponseStatusException.class,()->rollback.rollbackEvent(first.getId()));
        var latest=history.getRecentEventsByDomain(DomainScope.ACADEMICS,100).stream().filter(e->course.getId().equals(e.getEntityId())&&e.getAction()!=ActionType.ROLLBACK).findFirst().orElseThrow();
        rollback.rollbackEvent(latest.getId());assertEquals("Course after",courses.findById(course.getId()).orElseThrow().getName());
        assertTrue(assessments.existsById(assessment.getId()));assertTrue(categories.existsById(category.getId()));
    }
    @Test void assessmentConflictAndUndoPreserveCourseAndCategory(){
        Course course=courses.saveAndFlush(new Course("SCORE101","Scores","Term",BigDecimal.ONE,new BigDecimal("90")));
        AssessmentCategory category=categories.saveAndFlush(new AssessmentCategory(course,"Tests",new BigDecimal("100"),0));
        Assessment assessment=assessments.saveAndFlush(new Assessment(course,category,"Original test",new BigDecimal("100")));
        ObjectNode stale=mapper.createObjectNode().put("title","Changed test").put("updatedAt",assessments.findById(assessment.getId()).orElseThrow().getUpdatedAt().toString());
        records.assessment(assessment.getId(),stale);
        assertThrows(ResponseStatusException.class,()->records.assessment(assessment.getId(),stale));
        var event=history.getRecentEventsByDomain(DomainScope.ACADEMICS,100).stream().filter(e->assessment.getId().equals(e.getEntityId())).findFirst().orElseThrow();
        rollback.rollbackEvent(event.getId());Assessment restored=assessments.findById(assessment.getId()).orElseThrow();
        assertEquals("Original test",restored.getTitle());assertEquals(course.getId(),restored.getCourse().getId());assertEquals(category.getId(),restored.getCategory().getId());
    }
    @Test void noteAndEventUndoRejectLaterEdits(){
        for(String kind:List.of("note","event")){
            ObjectNode body=mapper.createObjectNode().put("domain","ACADEMICS").put("title","First "+kind);
            if(kind.equals("note"))body.set("content",mapper.createObjectNode().put("type","doc"));
            else body.put("allDay",true).put("startDate","2034-01-01").put("endDate","2034-01-02");
            Map<String,Object> saved=kind.equals("note")?data.saveNote(null,body):data.saveEvent(null,body);
            UUID id=(UUID)saved.get("id");
            ObjectNode first=mapper.valueToTree(saved);if(kind.equals("note"))first.set("content",body.get("content"));first.put("title","Second "+kind);
            var second=kind.equals("note")?data.saveNote(id,first):data.saveEvent(id,first);
            var entries=(List<Map<String,Object>>)data.history("ACADEMICS");UUID historyId=(UUID)entries.stream().filter(e->id.equals(e.get("recordId"))).findFirst().orElseThrow().get("id");
            ObjectNode later=mapper.valueToTree(second);if(kind.equals("note"))later.set("content",body.get("content"));later.put("title","Latest "+kind);
            if(kind.equals("note"))data.saveNote(id,later);else data.saveEvent(id,later);
            assertThrows(ResponseStatusException.class,()->data.undo(historyId,"ACADEMICS"));
        }
    }
    @Test void standaloneEventIsCreatedAndAuditedOnlyAfterSingleUseApproval(){
        int before=data.events("CAREER",false).size();
        var card=capture.createStructuredPreview(DomainScope.CAREER,"EVENT",Map.of("title","Reviewed overnight event", "startAt","2038-03-10T23:30:00Z","endAt","2038-03-11T00:20:00Z"));
        assertEquals(before,data.events("CAREER",false).size());
        UUID id=(UUID)capture.commitConfirmation(card.confirmationToken());
        var saved=data.events("CAREER",false).stream().filter(e->id.equals(e.get("id"))).findFirst().orElseThrow();
        assertEquals("Reviewed overnight event",saved.get("title"));assertEquals("CAREER",saved.get("domain"));
        var entries=(List<Map<String,Object>>)data.history("CAREER");assertTrue(entries.stream().anyMatch(e->id.equals(e.get("recordId"))));
        assertThrows(com.tracker.rag.dto.CardAlreadyConfirmedException.class,()->capture.commitConfirmation(card.confirmationToken()));
        assertEquals(before+1,data.events("CAREER",false).size());
    }
    @Test void eventPreviewRejectsUnexpectedFieldsAndInvalidRange(){
        assertThrows(IllegalArgumentException.class,()->capture.createStructuredPreview(DomainScope.PERSONAL,"EVENT",Map.of("title","Bad range","startAt","2038-03-10T11:00:00Z","endAt","2038-03-10T10:00:00Z")));
        assertThrows(IllegalArgumentException.class,()->capture.createStructuredPreview(DomainScope.PERSONAL,"EVENT",Map.of("title","Bad field","startAt","2038-03-10T11:00:00Z","endAt","2038-03-10T12:00:00Z","domain","CAREER")));
    }
    @Test void reminderThatExpiresAfterPreviewCannotBeSaved() throws Exception {
        var at=java.time.Instant.now().plusSeconds(3);
        var card=capture.createStructuredPreview(DomainScope.ACADEMICS,"EVENT",Map.of("title","Expired reminder proposal","startAt","2038-03-10T11:00:00Z","endAt","2038-03-10T12:00:00Z","remindAt",at.toString()));
        Thread.sleep(3100);
        assertThrows(IllegalArgumentException.class,()->capture.commitConfirmation(card.confirmationToken()));
        assertTrue(data.events("ACADEMICS",false).stream().noneMatch(e->"Expired reminder proposal".equals(e.get("title"))));
        capture.cancelPreviewCard(card.confirmationToken());
    }
}
