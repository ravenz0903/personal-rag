package com.tracker.rag.service.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.*;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.*;
import com.tracker.rag.service.EventLogService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service @Profile("desktop") @Transactional
public class DesktopTaskService {
    private final TaskRepository tasks;
    private final LocalDataService data;
    private final EventLogService events;
    private final ObjectMapper mapper;
    public DesktopTaskService(TaskRepository tasks,LocalDataService data,EventLogService events,ObjectMapper mapper){this.tasks=tasks;this.data=data;this.events=events;this.mapper=mapper;}
    public record TaskView(UUID id,String domain,String title,String description,Instant dueDate,Instant remindAt,String priority,String status,boolean archived,List<String> tags,String recurrence,UUID parentId,UUID successorId,long version,Instant updatedAt){}
    public static TaskView view(Task task){return new TaskView(task.getId(),task.getDomain().name(),task.getTitle(),task.getDescription(),task.getDueDate(),task.getRemindAt(),task.getPriority().name(),task.getStatus().name(),task.isArchived(),task.getTags(),task.getRecurrence(),task.getParentId(),task.getSuccessorId(),task.getVersion(),task.getUpdatedAt());}
    public List<TaskView> list(String domain){return tasks.findByDomainIn(LocalDataService.scopes(domain).stream().map(DomainScope::parse).toList()).stream().map(DesktopTaskService::view).toList();}
    public TaskView open(UUID id){return view(tasks.findById(id).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Task not found")));}
    public Task change(UUID id,DomainScope domain,long version,String status,Boolean archived,Instant dueDate,boolean reschedule){
        Task task=tasks.lockForEdit(id,domain).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Task not found in this area"));
        if(task.getVersion()!=version)LocalDataService.conflict();
        Object before=mapper.valueToTree(task);TaskStatus old=task.getStatus();
        if(status!=null){TaskStatus next=TaskStatus.valueOf(status);if(next!=TaskStatus.COMPLETED&&task.getSuccessorId()!=null)throw new IllegalArgumentException("Undo the completion from history before reopening this recurring task");task.setStatus(next);}
        if(archived!=null)task.setArchived(archived);
        if(reschedule){if(dueDate==null)throw new IllegalArgumentException("Choose a new deadline");if(task.getRemindAt()!=null&&task.getDueDate()!=null){Instant reminder=dueDate.minus(Duration.between(task.getRemindAt(),task.getDueDate()));if(!reminder.isAfter(Instant.now()))throw new IllegalArgumentException("Rescheduling would put the reminder in the past. Edit the task to choose a new reminder.");task.setRemindAt(reminder);}task.setDueDate(dueDate);task.setRecurrenceDay(dueDate.atZone(data.zone()).getDayOfMonth());task.setRecurrenceTime(dueDate.atZone(data.zone()).toLocalTime().toString().substring(0,5));}
        complete(task,old);tasks.saveAndFlush(task);events.recordMutation(domain,EntityType.TASK,id,ActionType.UPDATE,before,task);return task;
    }
    public Task toggle(UUID id){Task task=tasks.findById(id).orElseThrow(()->new IllegalArgumentException("Task not found"));return change(id,task.getDomain(),task.getVersion(),task.getStatus()==TaskStatus.COMPLETED?"PENDING":"COMPLETED",null,null,false);}
    public void details(Task task,Map<String,Object> payload,TaskStatus previous){
        if(task.getStatus()!=TaskStatus.COMPLETED&&task.getSuccessorId()!=null)throw new IllegalArgumentException("Undo completion from history before reopening this recurring task");
        Object rawTags=payload.get("tags");if(rawTags!=null)task.setTags((List<String>)rawTags);
        if(payload.containsKey("recurrence")){String recurrence=(String)payload.get("recurrence");if(!Set.of("NONE","DAILY","WEEKDAYS","WEEKLY","MONTHLY").contains(recurrence))throw new IllegalArgumentException("Invalid recurrence");task.setRecurrence(recurrence);}
        if(!task.getRecurrence().equals("NONE")&&task.getDueDate()==null)throw new IllegalArgumentException("Recurring tasks need a deadline");
        if(task.getRecurrenceDay()==null&&task.getDueDate()!=null)task.setRecurrenceDay(task.getDueDate().atZone(data.zone()).getDayOfMonth());
        if(task.getRecurrenceTime()==null&&task.getDueDate()!=null)task.setRecurrenceTime(task.getDueDate().atZone(data.zone()).toLocalTime().toString().substring(0,5));
        if(payload.containsKey("parentId")){
            String parentId=Objects.toString(payload.get("parentId"),"");
            if(parentId.isBlank())task.setParentTask(null);
            else {Task parent=tasks.findByIdAndDomain(UUID.fromString(parentId),task.getDomain()).orElseThrow(()->new IllegalArgumentException("Parent must belong to this area"));if(parent.getParentId()!=null||parent.isArchived()||parent.getId().equals(task.getId())||!tasks.findByParentTaskId(task.getId()==null?UUID.randomUUID():task.getId()).isEmpty())throw new IllegalArgumentException("Subtasks support one level and cannot contain their own children");task.setParentTask(parent);}
        }
        complete(task,previous);
    }
    private void complete(Task task,TaskStatus previous){
        if(previous==TaskStatus.COMPLETED||task.getStatus()!=TaskStatus.COMPLETED||task.getRecurrence().equals("NONE")||task.getSuccessorId()!=null||task.isArchived())return;
        if(task.getId()==null)tasks.saveAndFlush(task);
        if(task.getRecurrenceTime()==null)task.setRecurrenceTime(task.getDueDate().atZone(data.zone()).toLocalTime().toString().substring(0,5));
        Instant due=nextDue(task.getDueDate(),task.getRecurrence(),task.getRecurrenceDay(),task.getRecurrenceTime(),data.zone(),Instant.now());
        Task next=new Task(task.getDomain(),task.getTitle(),task.getPriority(),due);
        next.setDescription(task.getDescription());next.setTags(task.getTags());next.setRecurrence(task.getRecurrence());next.setRecurrenceDay(task.getRecurrenceDay());
        next.setRecurrenceTime(task.getRecurrenceTime());
        UUID series=task.getSeriesId()==null?task.getId():task.getSeriesId();task.setSeriesId(series);next.setSeriesId(series);
        next.setParentTask(task.getParentTask());next.setCourse(task.getCourse());next.setCareerApplication(task.getCareerApplication());
        if(task.getRemindAt()!=null){Instant reminder=due.minus(Duration.between(task.getRemindAt(),task.getDueDate()));if(reminder.isAfter(Instant.now()))next.setRemindAt(reminder);}
        next=tasks.saveAndFlush(next);task.setSuccessorId(next.getId());
    }
    public static Instant nextDue(Instant previous,String recurrence,Integer anchorDay,ZoneId zone,Instant now){
        return nextDue(previous,recurrence,anchorDay,null,zone,now);
    }
    public static Instant nextDue(Instant previous,String recurrence,Integer anchorDay,String anchorTime,ZoneId zone,Instant now){
        ZonedDateTime start=previous.atZone(zone),current=now.atZone(zone);LocalDate date=start.toLocalDate();LocalTime time=anchorTime==null?start.toLocalTime():LocalTime.parse(anchorTime);
        if(recurrence.equals("WEEKLY"))date=date.plusWeeks(Math.max(1,ChronoUnit.WEEKS.between(date,current.toLocalDate())));
        else if(recurrence.equals("MONTHLY")){YearMonth month=YearMonth.from(start).plusMonths(Math.max(1,ChronoUnit.MONTHS.between(YearMonth.from(start),YearMonth.from(current))));date=month.atDay(Math.min(anchorDay==null?start.getDayOfMonth():anchorDay,month.lengthOfMonth()));}
        else date=date.plusDays(Math.max(1,ChronoUnit.DAYS.between(date,current.toLocalDate())));
        for(int i=0;i<40;i++){
            ZonedDateTime candidate=date.atTime(time).atZone(zone);
            boolean weekday=date.getDayOfWeek()!=DayOfWeek.SATURDAY&&date.getDayOfWeek()!=DayOfWeek.SUNDAY;
            if(candidate.toInstant().isAfter(now)&&candidate.toInstant().isAfter(previous)&&(!recurrence.equals("WEEKDAYS")||weekday))return candidate.toInstant();
            if(recurrence.equals("WEEKLY"))date=date.plusWeeks(1);else if(recurrence.equals("MONTHLY")){YearMonth month=YearMonth.from(date).plusMonths(1);date=month.atDay(Math.min(anchorDay==null?start.getDayOfMonth():anchorDay,month.lengthOfMonth()));}else date=date.plusDays(1);
        }
        throw new IllegalArgumentException("Could not calculate next recurrence");
    }
}
