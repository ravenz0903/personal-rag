package com.tracker.rag.service.desktop;

import com.fasterxml.jackson.databind.*;
import com.tracker.rag.domain.DomainScope;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service @Profile("desktop")
public class LocalDataService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public LocalDataService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public List<Map<String,Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> {
            Map<String,Object> values=new LinkedHashMap<>();
            for (int i=1;i<=rs.getMetaData().getColumnCount();i++) {
                String name=rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT);
                var match=java.util.regex.Pattern.compile("_([a-z])").matcher(name);
                name=match.replaceAll(m -> m.group(1).toUpperCase(Locale.ROOT));
                Object value=rs.getObject(i);
                if(value instanceof Clob c) value=c.getSubString(1,(int)c.length());
                if(value instanceof OffsetDateTime d) value=d.toInstant().toString();
                if(value instanceof Timestamp d) value=d.toInstant().toString();
                if(value instanceof java.sql.Date d) value=d.toLocalDate().toString();
                values.put(name,value);
            }
            return values;
        },args);
    }
    public Map<String,Object> preferences() { return rows("SELECT * FROM local_preferences WHERE id=1").getFirst(); }
    public ZoneId zone() { return ZoneId.of((String)preferences().get("timezone")); }
    @Transactional public Map<String,Object> preferences(JsonNode input) {
        String name=text(input,"displayName",80), timezone=text(input,"timezone",80); ZoneId.of(timezone);
        int week=input.path("weekStart").asInt(-1); if(week!=0&&week!=1) throw new IllegalArgumentException("Choose Sunday or Monday");
        jdbc.update("UPDATE local_preferences SET display_name=?, timezone=?, week_start=?, initialized=TRUE, reminders_enabled=? WHERE id=1", name,timezone,week,input.path("remindersEnabled").asBoolean(true));
        return preferences();
    }
    public static String text(JsonNode input,String field,int max) {
        JsonNode value=input.get(field);
        if(value==null||!value.isTextual()||value.asText().isBlank()||value.asText().length()>max) throw new IllegalArgumentException("Provide a valid "+field+" (maximum "+max+" characters)");
        return value.asText().trim();
    }
    public static String optional(JsonNode input,String field,int max) {
        JsonNode value=input.get(field); if(value==null||value.isNull())return "";
        if(!value.isTextual()||value.asText().length()>max)throw new IllegalArgumentException("Invalid "+field);
        return value.asText().trim();
    }
    public static List<String> tags(JsonNode input) {
        JsonNode tags=input.path("tags"); if(tags.isMissingNode()||tags.isNull())return List.of();
        if(!tags.isArray()||tags.size()>30)throw new IllegalArgumentException("Choose at most 30 tags");
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for(JsonNode tag:tags){ if(!tag.isTextual()||tag.asText().isBlank()||tag.asText().length()>40||tag.asText().contains(","))throw new IllegalArgumentException("Tags must be short text without commas");result.add(tag.asText().trim()); }
        return List.copyOf(result);
    }
    public static void conflict() { throw new ResponseStatusException(HttpStatus.CONFLICT,"This record changed in another window. Reload it before saving."); }
    public static void missing() { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found in this area"); }
    public static List<String> scopes(String domain) { return domain.equals("ALL")?List.of("ACADEMICS","CAREER"):List.of(DomainScope.parse(domain).name()); }
    private String scopeSql(String domain) { return domain.equals("ALL")?"domain IN ('ACADEMICS','CAREER')":"domain='"+DomainScope.parse(domain).name()+"'"; }
    private Map<String,Object> noteView(Map<String,Object> note) {
        try { note.put("content",mapper.readTree((String)note.get("content"))); }catch(Exception e){throw new IllegalStateException("Stored note is invalid",e);}
        String tags=(String)note.get("tags"); note.put("tags",tags.isBlank()?List.of():List.of(tags.split(","))); return note;
    }
    public List<Map<String,Object>> notes(String domain,boolean archived) { return rows("SELECT id,domain,title,tags,pinned,archived,revision,created_at,updated_at FROM local_notes WHERE "+scopeSql(domain)+" AND archived=? ORDER BY pinned DESC,updated_at DESC",archived).stream().map(n->{String tags=(String)n.get("tags"); n.put("tags",tags.isBlank()?List.of():List.of(tags.split(",")));return n;}).toList(); }
    public Map<String,Object> note(UUID id,String domain) { var rows=rows("SELECT * FROM local_notes WHERE id=? AND "+scopeSql(domain),id); if(rows.isEmpty())missing(); return noteView(rows.getFirst()); }
    @Transactional public Map<String,Object> saveNote(UUID id,JsonNode input) {
        String domain=text(input,"domain",32);DomainScope.parse(domain);
        String title=text(input,"title",255),tags=String.join(",",tags(input));
        JsonNode content=input.path("content");String json=content.toString();
        if(json.length()>250000||!content.path("type").asText().equals("doc"))throw new IllegalArgumentException("Note is too large or invalid");
        StringBuilder plain=new StringBuilder();validateContent(content,plain,0);
        Map<String,Object> before=id==null?null:note(id,domain);
        Instant now=Instant.now();
        if(id==null){id=UUID.randomUUID();jdbc.update("INSERT INTO local_notes(id,domain,title,content,plain_text,tags,pinned,archived,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,domain,title,json,plain.toString(),tags,input.path("pinned").asBoolean(),false,Timestamp.from(now),Timestamp.from(now));}
        else {
            var existing=note(id,domain); long revision=input.path("revision").asLong(-1);
            if(jdbc.update("UPDATE local_notes SET title=?,content=?,plain_text=?,tags=?,pinned=?,archived=?,revision=revision+1,updated_at=? WHERE id=? AND domain=? AND revision=?",title,json,plain.toString(),tags,input.path("pinned").asBoolean(),input.path("archived").asBoolean(),Timestamp.from(now),id,domain,revision)!=1)conflict();
        }
        var saved=note(id,domain);audit("note",id,domain,before,saved);return saved;
    }
    private static final Set<String> NODES=Set.of("doc","paragraph","heading","text","bulletList","orderedList","listItem","taskList","taskItem","blockquote","hardBreak","horizontalRule");
    public static void validateContent(JsonNode node,StringBuilder plain,int depth) {
        if(depth>20||!node.isObject()||!NODES.contains(node.path("type").asText()))throw new IllegalArgumentException("Unsupported note content");
        String type=node.path("type").asText();
        if(type.equals("text")){String value=node.path("text").asText();if(value.length()>50000)throw new IllegalArgumentException("Note text is too long");plain.append(value);}
        if(type.equals("heading")&&(node.path("attrs").path("level").asInt()<1||node.path("attrs").path("level").asInt()>3))throw new IllegalArgumentException("Choose a heading from 1 to 3");
        JsonNode marks=node.path("marks"); if(!marks.isMissingNode()&&!marks.isArray())throw new IllegalArgumentException("Invalid note formatting");
        for(JsonNode mark:marks){String kind=mark.path("type").asText();if(!Set.of("bold","italic","link").contains(kind))throw new IllegalArgumentException("Unsupported note formatting");if(kind.equals("link")){String href=mark.path("attrs").path("href").asText();if(href.length()>2048||!href.matches("(?i)^(https?://|mailto:)[^\\s]+$"))throw new IllegalArgumentException("Links must use https, http, or mailto");}}
        JsonNode children=node.path("content");if(!children.isMissingNode()&&!children.isArray())throw new IllegalArgumentException("Invalid note blocks");
        for(JsonNode child:children)validateContent(child,plain,depth+1);
        if(Set.of("paragraph","heading","hardBreak","listItem","taskItem").contains(type))plain.append('\n');
        if(plain.length()>100000)throw new IllegalArgumentException("Note text exceeds 100,000 characters");
    }
    public static String markdown(JsonNode node) {
        String type=node.path("type").asText(); StringBuilder content=new StringBuilder();for(JsonNode child:node.path("content"))content.append(markdown(child));
        if(type.equals("orderedList")){StringBuilder list=new StringBuilder();int index=node.path("attrs").path("start").asInt(1);for(JsonNode child:node.path("content"))list.append(markdown(child).replaceFirst("^- ",index+++". "));return list+"\n";}
        if(type.equals("blockquote"))return "> "+content.toString().strip().replace("\n","\n> ")+"\n\n";
        if(type.equals("text")){String value=node.path("text").asText().replace("\\","\\\\").replace("*","\\*").replace("[","\\[").replace("]","\\]").replace("_","\\_");for(JsonNode mark:node.path("marks")){String kind=mark.path("type").asText();if(kind.equals("bold"))value="**"+value+"**";if(kind.equals("italic"))value="*"+value+"*";if(kind.equals("link"))value="["+value+"]("+mark.path("attrs").path("href").asText().replace(")","%29")+")";}return value;}
        return switch(type){case "heading"->"#".repeat(node.path("attrs").path("level").asInt(1))+" "+content+"\n\n";case "paragraph"->content+"\n\n";case "taskItem"->"- ["+(node.path("attrs").path("checked").asBoolean()?"x":" ")+"] "+content.toString().strip()+"\n";case "listItem"->"- "+content.toString().strip()+"\n";case "hardBreak"->"  \n";case "horizontalRule"->"\n---\n";default->content.toString();};
    }
    public List<Map<String,Object>> events(String domain,boolean archived) { return rows("SELECT * FROM calendar_events WHERE "+scopeSql(domain)+" AND archived=? ORDER BY COALESCE(start_date,CAST(start_at AS DATE))",archived); }
    @Transactional public Map<String,Object> saveEvent(UUID id,JsonNode input) {
        String domain=text(input,"domain",32);DomainScope.parse(domain);String title=text(input,"title",255),description=optional(input,"description",4000);
        boolean allDay=input.path("allDay").asBoolean();Instant start=null,end=null,reminder=null;LocalDate from=null,to=null;
        if(allDay){from=LocalDate.parse(text(input,"startDate",10));to=LocalDate.parse(text(input,"endDate",10));if(!to.isAfter(from))throw new IllegalArgumentException("End date must follow start date");}
        else{start=Instant.parse(text(input,"startAt",50));end=Instant.parse(text(input,"endAt",50));if(!end.isAfter(start))throw new IllegalArgumentException("End time must follow start time");}
        String remind=optional(input,"remindAt",50);if(!remind.isBlank())reminder=Instant.parse(remind);
        var existing=id==null?List.<Map<String,Object>>of():rows("SELECT * FROM calendar_events WHERE id=? AND domain=?",id,domain);
        if(id!=null&&existing.isEmpty())missing();
        if(reminder!=null&&!reminder.isAfter(Instant.now())&&(existing.isEmpty()||!reminder.toString().equals(existing.getFirst().get("remindAt"))))throw new IllegalArgumentException("Choose a future reminder");
        Object[] values={title,description,allDay,start==null?null:Timestamp.from(start),end==null?null:Timestamp.from(end),from,to,reminder==null?null:Timestamp.from(reminder),input.path("archived").asBoolean(),Timestamp.from(Instant.now())};
        if(id==null){id=UUID.randomUUID();List<Object> args=new ArrayList<>(Arrays.asList(values));args.add(id);args.add(domain);args.add(Timestamp.from(Instant.now()));jdbc.update("INSERT INTO calendar_events(title,description,all_day,start_at,end_at,start_date,end_date,remind_at,archived,updated_at,id,domain,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",args.toArray());}
        else{List<Object> args=new ArrayList<>(Arrays.asList(values));args.add(id);args.add(domain);args.add(input.path("revision").asLong(-1));if(jdbc.update("UPDATE calendar_events SET title=?,description=?,all_day=?,start_at=?,end_at=?,start_date=?,end_date=?,remind_at=?,archived=?,updated_at=?,revision=revision+1 WHERE id=? AND domain=? AND revision=?",args.toArray())!=1)conflict();}
        var saved=rows("SELECT * FROM calendar_events WHERE id=?",id).getFirst();audit("event",id,domain,existing.isEmpty()?null:existing.getFirst(),saved);return saved;
    }
    private void audit(String kind,UUID id,String domain,Object before,Map<String,Object> after){
        try{jdbc.update("INSERT INTO local_history(id,record_id,kind,domain,action,before_json,after_json,expected_revision,timestamp) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),id,kind,domain,before==null?"CREATE":"UPDATE",before==null?null:mapper.writeValueAsString(before),mapper.writeValueAsString(after),after.get("revision"),Instant.now());}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    public Object history(String domain){return rows("SELECT h.id,h.record_id,h.kind,h.domain,h.action,h.undone,h.timestamp,COALESCE(n.title,e.title,'Record') AS title FROM local_history h LEFT JOIN local_notes n ON n.id=h.record_id AND h.kind='note' LEFT JOIN calendar_events e ON e.id=h.record_id AND h.kind='event' WHERE "+scopeSql(domain).replace("domain","h.domain")+" ORDER BY h.timestamp DESC LIMIT 50");}
    @Transactional public void undo(UUID historyId,String domain){
        var found=rows("SELECT * FROM local_history WHERE id=? AND "+scopeSql(domain)+" FOR UPDATE",historyId);if(found.isEmpty())missing();var event=found.getFirst();if(Boolean.TRUE.equals(event.get("undone")))conflict();
        String kind=(String)event.get("kind"),table=kind.equals("note")?"local_notes":"calendar_events";UUID id=(UUID)event.get("recordId");
        var current=rows("SELECT * FROM "+table+" WHERE id=? FOR UPDATE",id);if(current.isEmpty())missing();if(!Objects.equals(((Number)current.getFirst().get("revision")).longValue(),((Number)event.get("expectedRevision")).longValue()))conflict();
        try{var input=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree((String)Objects.requireNonNullElse(event.get("beforeJson"),event.get("afterJson")));input.put("revision",((Number)current.getFirst().get("revision")).longValue());if(event.get("beforeJson")==null)input.put("archived",true);if(kind.equals("note"))saveNote(id,input);else saveEvent(id,input);jdbc.update("UPDATE local_history SET undone=TRUE WHERE id=?",historyId);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    public Map<String,Object> search(String query,String domain,int page) {
        if(query.length()>200||page<0||page>10000)throw new IllegalArgumentException("Invalid search");
        List<String> scopes=scopes(domain);String in=scopes.size()==2?"IN (?,?)":"IN (?)";
        String union="SELECT id,domain,title,'task' AS kind,COALESCE(description,'') AS detail FROM tasks WHERE archived=FALSE UNION ALL SELECT id,domain,title,'note',plain_text FROM local_notes WHERE archived=FALSE UNION ALL SELECT id,domain,title,'event',description FROM calendar_events WHERE archived=FALSE UNION ALL SELECT id,domain,name,'course',code FROM courses WHERE archived=FALSE UNION ALL SELECT id,domain,company,'application',position FROM career_applications WHERE archived=FALSE UNION ALL SELECT id,domain,file_name,'document',content_type FROM document_records";
        String where=" FROM ("+union+") records WHERE domain "+in+" AND (LOWER(title) LIKE ? ESCAPE '!' OR LOWER(detail) LIKE ? ESCAPE '!')";
        String pattern="%"+query.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        List<Object> params=new ArrayList<>(scopes);params.add(pattern);params.add(pattern);
        long count=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,params.toArray());params.add(40);params.add(page*40);
        return Map.of("results",rows("SELECT id,domain,title,kind,SUBSTRING(detail,1,160) AS detail"+where+" ORDER BY kind,title,id LIMIT ? OFFSET ?",params.toArray()),"total",count,"page",page);
    }
}
