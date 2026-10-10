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
        var candidates=data.rows("SELECT id,'task' AS kind,remind_at FROM tasks WHERE archived=FALSE AND status IN ('PENDING','IN_PROGRESS') AND remind_at BETWEEN ? AND ? UNION ALL SELECT id,'event',remind_at FROM calendar_events WHERE archived=FALSE AND remind_at BETWEEN ? AND ? ORDER BY remind_at LIMIT 20",now.minusSeconds(86400),now,now.minusSeconds(86400),now);
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
