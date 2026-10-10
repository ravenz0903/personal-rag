package com.tracker.rag.service.desktop;

import com.fasterxml.jackson.databind.*;
import com.tracker.rag.domain.*;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.*;
import com.tracker.rag.service.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service @Profile("desktop")
public class LocalRecordService {
    private final LocalDataService data;private final JdbcTemplate jdbc;private final HabitRepository habits;private final CourseRepository courses;private final CareerApplicationRepository applications;private final AssessmentRepository assessments;private final EventLogService events;private final ObjectMapper mapper;
    public LocalRecordService(LocalDataService data,JdbcTemplate jdbc,HabitRepository habits,CourseRepository courses,CareerApplicationRepository applications,AssessmentRepository assessments,EventLogService events,ObjectMapper mapper){this.data=data;this.jdbc=jdbc;this.habits=habits;this.courses=courses;this.applications=applications;this.assessments=assessments;this.events=events;this.mapper=mapper;}
    private String table(String kind){return switch(kind){case "habit"->"habits";case "course"->"courses";case "application"->"career_applications";default->throw new IllegalArgumentException("Unknown record type");};}
    public List<Map<String,Object>> records(String kind){return data.rows("SELECT * FROM "+table(kind)+" ORDER BY archived,name".replace("name",kind.equals("application")?"company":kind.equals("course")?"name":"name"));}
    @Transactional public Object update(String kind,UUID id,JsonNode input){
        var found=data.rows("SELECT * FROM "+table(kind)+" WHERE id=? FOR UPDATE",id);if(found.isEmpty())LocalDataService.missing();
        if(!Objects.toString(found.getFirst().get("updatedAt"),"").equals(input.path("updatedAt").asText()))LocalDataService.conflict();
        boolean archived=input.path("archived").asBoolean();
        if(kind.equals("habit")){
            Habit habit=habits.findById(id).orElseThrow();Object before=mapper.valueToTree(habit);
            habit.setName(LocalDataService.text(input,"name",255));habit.setFrequency(HabitFrequency.valueOf(LocalDataService.text(input,"frequency",30)));
            if(habit.getFrequency()==HabitFrequency.CUSTOM_DAYS && habit.getCustomDaysMask()==null)throw new IllegalArgumentException("Choose Daily, Weekdays, or Weekends");
            String zone=LocalDataService.text(input,"timezone",64);ZoneId.of(zone);habit.setTimezone(zone);habit.setArchived(archived);habits.saveAndFlush(habit);events.recordMutation(DomainScope.PERSONAL,EntityType.HABIT,id,ActionType.UPDATE,before,habit);return habit;
        }
        if(kind.equals("course")){
            Course course=courses.findById(id).orElseThrow();Object before=mapper.valueToTree(course);
            String code=LocalDataService.text(input,"code",32).toUpperCase(Locale.ROOT);courses.findByCodeIgnoreCase(code).filter(c->!c.getId().equals(id)).ifPresent(c->{throw new IllegalArgumentException("Another course uses this code");});
            BigDecimal target=new BigDecimal(input.path("targetGrade").asText());if(target.signum()<0||target.compareTo(new BigDecimal("100"))>0)throw new IllegalArgumentException("Target grade must be 0–100");
            course.setCode(code);course.setName(LocalDataService.text(input,"name",255));course.setTerm(LocalDataService.text(input,"term",64));course.setTargetGrade(target);course.setArchived(archived);courses.saveAndFlush(course);events.recordMutation(DomainScope.ACADEMICS,EntityType.COURSE,id,ActionType.UPDATE,before,course);return course;
        }
        CareerApplication application=applications.findById(id).orElseThrow();Object before=mapper.valueToTree(application);
        application.setCompany(LocalDataService.text(input,"company",255));application.setPosition(LocalDataService.text(input,"position",255));application.setStatus(CareerStatus.valueOf(LocalDataService.text(input,"status",40)));application.setNotes(LocalDataService.optional(input,"notes",4000));application.setArchived(archived);applications.saveAndFlush(application);events.recordMutation(DomainScope.CAREER,EntityType.CAREER_APPLICATION,id,ActionType.UPDATE,before,application);return application;
    }
    @Transactional public Object assessment(UUID id,JsonNode input){
        if(data.rows("SELECT id FROM assessments WHERE id=? FOR UPDATE",id).isEmpty())LocalDataService.missing();
        Assessment assessment=assessments.findByIdAndCourseDomainIn(id,Set.of(DomainScope.ACADEMICS)).orElseThrow(()->new IllegalArgumentException("Assessment not found"));
        if(!assessment.getUpdatedAt().toString().equals(input.path("updatedAt").asText()))LocalDataService.conflict();
        Object before=mapper.valueToTree(assessment);assessment.setTitle(LocalDataService.text(input,"title",255));String date=LocalDataService.optional(input,"dueDate",50);assessment.setDueDate(date.isBlank()?null:Instant.parse(date));assessments.saveAndFlush(assessment);events.recordMutation(DomainScope.ACADEMICS,EntityType.ASSESSMENT,id,ActionType.UPDATE,before,assessment);return Map.of("id",id);
    }
    public Map<String,Object> progress(String domain,int days){
        if(days!=7&&days!=30)throw new IllegalArgumentException("Choose 7 or 30 days");List<String> scopes=LocalDataService.scopes(domain);String in=scopes.size()==2?"IN (?,?)":"IN (?)";
        LocalDate today=LocalDate.now(data.zone());List<Map<String,Object>> trend=new ArrayList<>();
        for(int offset=days-1;offset>=0;offset--){LocalDate day=today.minusDays(offset);List<Object> args=new ArrayList<>(scopes);args.add(java.sql.Timestamp.from(day.atStartOfDay(data.zone()).toInstant()));args.add(java.sql.Timestamp.from(day.plusDays(1).atStartOfDay(data.zone()).toInstant()));long count=jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE domain "+in+" AND status='COMPLETED' AND completed_at>=? AND completed_at<?",Long.class,args.toArray());trend.add(Map.of("date",day.toString(),"completed",count));}
        List<Map<String,Object>> habitProgress=new ArrayList<>();
        if(scopes.contains("PERSONAL"))for(Habit habit:habits.findByDomain(DomainScope.PERSONAL).stream().filter(h->!h.isArchived()).toList()){
            LocalDate habitToday=LocalDate.now(habit.getZoneId());int scheduled=0,done=0;
            for(int offset=days-1;offset>=0;offset--){LocalDate day=habitToday.minusDays(offset);if(day.isBefore(habit.getCreatedAt().atZone(habit.getZoneId()).toLocalDate()))continue;int weekday=day.getDayOfWeek().getValue();boolean expected=switch(habit.getFrequency()){case DAILY->true;case WEEKDAYS->weekday<=5;case WEEKENDS->weekday>=6;case CUSTOM_DAYS->((habit.getCustomDaysMask()==null?127:habit.getCustomDaysMask())&(1<<(weekday-1)))!=0;};if(expected){scheduled++;if(jdbc.queryForObject("SELECT COUNT(*) FROM habit_logs WHERE habit_id=? AND log_date=?",Long.class,habit.getId(),day)>0)done++;}}
            habitProgress.add(Map.of("name",habit.getName(),"scheduled",scheduled,"completed",done,"percent",scheduled==0?0:Math.round(100.0*done/scheduled)));
        }
        return Map.of("days",days,"tasks",trend,"habits",habitProgress,"career",scopes.contains("CAREER")?data.rows("SELECT status,COUNT(*) AS count FROM career_applications WHERE archived=FALSE GROUP BY status"):List.of());
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> export(){Map<String,Object> result=new LinkedHashMap<>();result.put("formatVersion",1);result.put("exportedAt",Instant.now());result.put("settings",data.preferences());for(String table:List.of("tasks","habits","habit_logs","courses","assessment_categories","assessments","career_applications","calendar_events","local_notes","document_records","event_log","local_history"))result.put(table,data.rows("SELECT * FROM "+table));return result;}
}
