package com.tracker.rag.service.desktop;

import com.tracker.rag.domain.*;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.TaskRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service @Profile("desktop")
public class DesktopUndoGuard {
    private final JdbcTemplate jdbc;private final TaskRepository tasks;private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public DesktopUndoGuard(JdbcTemplate jdbc,TaskRepository tasks,com.fasterxml.jackson.databind.ObjectMapper mapper){this.jdbc=jdbc;this.tasks=tasks;this.mapper=mapper;}
    public void check(EventLogEntry event){
        Long later=jdbc.queryForObject("SELECT COUNT(*) FROM event_log WHERE entity_id=? AND entity_type=? AND timestamp>? AND undone=FALSE AND action<>'ROLLBACK'",Long.class,event.getEntityId(),event.getEntityType().name(),java.sql.Timestamp.from(event.getTimestamp()));
        if(later>0)reject("Undo the newer change to this record first");
        if(event.getEntityType()==EntityType.TASK){
            Task current=tasks.lockForEdit(event.getEntityId(),event.getDomain()).orElse(null);
            if(current!=null&&event.getPayloadAfter()!=null)try{var after=mapper.readTree(event.getPayloadAfter());if(after.has("version")&&after.path("version").asLong()!=current.getVersion())reject("This task changed after that history entry");}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("Invalid task history",e);}
            boolean generated=false;
            try { generated=event.getPayloadBefore()!=null && !mapper.readTree(event.getPayloadBefore()).hasNonNull("successorId") && mapper.readTree(event.getPayloadAfter()).hasNonNull("successorId"); } catch(Exception e){throw new IllegalArgumentException("Cannot verify the recurring task history",e);}
            if(current!=null&&current.getSuccessorId()!=null&&generated){
                Task successor=tasks.lockForEdit(current.getSuccessorId(),current.getDomain()).orElse(null);
                if(successor==null||successor.getVersion()!=0||successor.isArchived()||successor.getStatus()!=TaskStatus.PENDING||!tasks.findByParentTaskId(successor.getId()).isEmpty())reject("The next recurring task has changed. It cannot be removed by undo.");
                tasks.delete(successor);tasks.flush();current.setSuccessorId(null);
            }
            if(event.getAction()==ActionType.CREATE&&!tasks.findByParentTaskId(event.getEntityId()).isEmpty())reject("Remove or undo the subtasks before undoing their parent");
        }
        if(event.getAction()==ActionType.CREATE){
            if(event.getEntityType()==EntityType.COURSE&&jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE course_id=?",Long.class,event.getEntityId())>0)reject("This course has related tasks. Archive it instead.");
            if(event.getEntityType()==EntityType.CAREER_APPLICATION&&jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE career_application_id=?",Long.class,event.getEntityId())>0)reject("This application has related tasks. Archive it instead.");
            String table=switch(event.getEntityType()){case COURSE->"assessment_categories";case HABIT->"habit_logs";case ASSESSMENT_CATEGORY->"assessments";default->null;};
            String key=switch(event.getEntityType()){case COURSE->"course_id";case HABIT->"habit_id";case ASSESSMENT_CATEGORY->"category_id";default->null;};
            if(table!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+key+"=?",Long.class,event.getEntityId())>0)reject("This record has related history. Archive it or undo the related changes first.");
        }
    }
    private static void reject(String message){throw new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
