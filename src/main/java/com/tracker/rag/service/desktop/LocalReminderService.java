package com.tracker.rag.service.desktop;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @Profile("desktop")
public class LocalReminderService {
    private final JdbcTemplate jdbc; private final LocalDataService data;
    public LocalReminderService(JdbcTemplate jdbc,LocalDataService data){this.jdbc=jdbc;this.data=data;}
    @Transactional public List<Map<String,Object>> claim(){
        if(!Boolean.TRUE.equals(data.preferences().get("remindersEnabled")))return List.of();
        Instant now=Instant.now();
        // Filter unclaimable attempts before limiting the batch, otherwise the
        // earliest delivered/leased reminders can indefinitely starve later ones.
        var candidates=data.rows("""
            SELECT due.id,due.kind,due.remind_at FROM (
                SELECT id,'task' AS kind,remind_at FROM tasks
                WHERE archived=FALSE AND status IN ('PENDING','IN_PROGRESS') AND remind_at BETWEEN ? AND ?
                UNION ALL
                SELECT id,'event' AS kind,remind_at FROM calendar_events
                WHERE archived=FALSE AND remind_at BETWEEN ? AND ?
            ) due
            WHERE NOT EXISTS (
                SELECT 1 FROM desktop_reminder_attempts attempt
                WHERE attempt.record_id=due.id AND attempt.kind=due.kind AND attempt.remind_at=due.remind_at
                AND (attempt.delivered_at IS NOT NULL OR attempt.attempts>=3 OR attempt.lease_until>=?)
            )
            ORDER BY due.remind_at,due.id,due.kind LIMIT 20
            """,now.minusSeconds(86400),now,now.minusSeconds(86400),now,now);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var row:candidates){
            Object id=row.get("id"),kind=row.get("kind");Instant at=Instant.parse((String)row.get("remindAt"));
            jdbc.update("INSERT INTO desktop_reminder_attempts(record_id,kind,remind_at) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM desktop_reminder_attempts WHERE record_id=? AND kind=? AND remind_at=?)",id,kind,at,id,kind,at);
            if(jdbc.update("UPDATE desktop_reminder_attempts SET attempts=attempts+1,lease_until=? WHERE record_id=? AND kind=? AND remind_at=? AND delivered_at IS NULL AND attempts<3 AND (lease_until IS NULL OR lease_until<?)",now.plusSeconds(120),id,kind,at,now)==1)result.add(Map.of("taskId",id,"kind",kind,"remindAt",at));
        }
        return result;
    }
    public void finish(UUID id,String kind,Instant at,boolean delivered){
        if(!Set.of("task","event").contains(kind)||id==null||at==null)throw new IllegalArgumentException("Invalid reminder");
        jdbc.update(delivered?"UPDATE desktop_reminder_attempts SET delivered_at=CURRENT_TIMESTAMP,last_error=NULL WHERE record_id=? AND kind=? AND remind_at=?":"UPDATE desktop_reminder_attempts SET last_error='Windows did not confirm delivery' WHERE record_id=? AND kind=? AND remind_at=?",id,kind,at);
    }
    public boolean eligible(UUID id,String kind,Instant at){
        if(!Boolean.TRUE.equals(data.preferences().get("remindersEnabled")))return false;
        String table=switch(kind){case "task"->"tasks";case "event"->"calendar_events";default->throw new IllegalArgumentException("Invalid reminder type");};
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=? AND remind_at=? AND archived=FALSE"+(kind.equals("task")?" AND status IN ('PENDING','IN_PROGRESS')":""),Long.class,id,at)>0;
    }
    public Object status(){return data.rows("SELECT kind,attempts,last_error,delivered_at,remind_at FROM desktop_reminder_attempts ORDER BY remind_at DESC LIMIT 20");}
}
