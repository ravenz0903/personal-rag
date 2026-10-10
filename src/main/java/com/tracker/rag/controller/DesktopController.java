package com.tracker.rag.controller;

import com.tracker.rag.model.TaskStatus;
import com.tracker.rag.repository.TaskRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** Local-only desktop operations. Every endpoint passes DesktopApiGuard. */
@RestController @Profile("desktop") @RequestMapping("/api/desktop")
public class DesktopController {
    private final TaskRepository tasks;
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private com.tracker.rag.service.desktop.LocalReminderService localReminders;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.context.ConfigurableApplicationContext context;
    public DesktopController(TaskRepository tasks, JdbcTemplate jdbc) { this.tasks = tasks; this.jdbc = jdbc; }
    @PostConstruct void schema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS desktop_reminder_deliveries (task_id UUID NOT NULL, remind_at TIMESTAMP WITH TIME ZONE NOT NULL, delivered_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY(task_id, remind_at))");
    }
    public record Reminder(UUID taskId, Instant remindAt) {}
    public record Delivery(UUID taskId, String kind, Instant remindAt) {}
    @GetMapping("/reminders/claim") public Object claim(){return localReminders.claim();}
    @GetMapping("/reminders/eligible") public Object eligible(@RequestParam UUID id,@RequestParam String kind,@RequestParam Instant at){return Map.of("eligible",localReminders.eligible(id,kind,at));}
    @PostMapping("/reminders/delivered") public Object delivered(@RequestBody Delivery delivery){localReminders.finish(delivery.taskId(),delivery.kind(),delivery.remindAt(),true);return Map.of("ok",true);}
    @PostMapping("/reminders/failed") public Object failed(@RequestBody Delivery delivery){localReminders.finish(delivery.taskId(),delivery.kind(),delivery.remindAt(),false);return Map.of("ok",true);}
    @GetMapping("/reminders/status") public Object status(){return localReminders.status();}
    @PostMapping("/shutdown") public Map<String, Boolean> shutdown() {
        Thread.ofVirtual().name("desktop-shutdown").start(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            System.exit(org.springframework.boot.SpringApplication.exit(context));
        });
        return Map.of("ok", true);
    }
    @GetMapping("/reminders/due") public List<Reminder> due() {
        Instant now = Instant.now();
        return tasks.findAll().stream().filter(t -> t.getRemindAt() != null && !t.getRemindAt().isAfter(now) && t.getRemindAt().isAfter(now.minusSeconds(86400)))
                .filter(t -> t.getStatus() == TaskStatus.PENDING || t.getStatus() == TaskStatus.IN_PROGRESS)
                .filter(t -> !t.isArchived())
                .filter(t -> jdbc.queryForObject("SELECT COUNT(*) FROM desktop_reminder_deliveries WHERE task_id = ? AND remind_at = ?", Long.class, t.getId(), java.sql.Timestamp.from(t.getRemindAt())) == 0)
                .sorted(Comparator.comparing(t -> t.getRemindAt())).limit(20).map(t -> new Reminder(t.getId(), t.getRemindAt())).toList();
    }
    @PostMapping("/reminders/ack") public Map<String, Boolean> ack(@RequestBody Reminder reminder) {
        if (reminder.taskId() == null || reminder.remindAt() == null) throw new IllegalArgumentException("Missing reminder");
        tasks.findById(reminder.taskId()).filter(t -> reminder.remindAt().equals(t.getRemindAt()))
                .filter(t -> t.getStatus() == TaskStatus.PENDING || t.getStatus() == TaskStatus.IN_PROGRESS)
                .ifPresent(t -> jdbc.update("MERGE INTO desktop_reminder_deliveries (task_id, remind_at, delivered_at) KEY(task_id, remind_at) VALUES (?, ?, CURRENT_TIMESTAMP)", t.getId(), java.sql.Timestamp.from(t.getRemindAt())));
        return Map.of("ok", true);
    }
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.core.env.Environment environment;
    @GetMapping("/backup") public synchronized void backup(jakarta.servlet.http.HttpServletResponse response) throws IOException {
        String url=environment.getRequiredProperty("spring.datasource.url");
        if(!url.startsWith("jdbc:h2:file:"))throw new IllegalArgumentException("Desktop backup requires a file database");
        Path directory=Path.of(url.substring("jdbc:h2:file:".length()).split(";")[0]).toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path databaseZip=Files.createTempFile(directory,"folio-database-",".zip");
        Path archive=Files.createTempFile(directory,"folio-archive-",".zip");
        try {
            jdbc.execute("BACKUP TO '"+databaseZip.toString().replace("'","''")+"'");
            try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(archive))){
                List<Map<String,Object>> manifest=new ArrayList<>();
                addFile(zip,databaseZip,"database.zip",manifest);
                Path documents=Path.of(environment.getProperty("tracker.documents.root",directory.resolve("documents").toString())).toAbsolutePath();
                if(Files.exists(documents))try(var files=Files.walk(documents)){
                    for(Path file:files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&!p.getFileName().toString().endsWith(".tmp")).toList())addFile(zip,file,"documents/"+documents.relativize(file).toString().replace('\\','/'),manifest);
                }
                zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(Map.of("formatVersion",1,"files",manifest)));zip.closeEntry();
                zip.putNextEntry(new ZipEntry("RESTORE.txt"));zip.write("Use Settings > Restore backup. Folio validates the archive and keeps the previous workspace before replacement. Manual recovery: quit Folio from the tray, keep a copy of data/, extract database.zip into data/, and copy documents/ alongside tracker.mv.db. Never replace a running database.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
            response.setContentType("application/zip");response.setHeader("Content-Disposition","attachment; filename=Folio-backup.zip");response.setContentLengthLong(Files.size(archive));Files.copy(archive,response.getOutputStream());
        } finally {Files.deleteIfExists(databaseZip);Files.deleteIfExists(archive);}
    }
    private void addFile(ZipOutputStream zip,Path file,String name,List<Map<String,Object>> manifest)throws IOException{
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            zip.putNextEntry(new ZipEntry(name));long count=0;
            try(var input=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int length;(length=input.read(buffer))!=-1;){zip.write(buffer,0,length);digest.update(buffer,0,length);count+=length;}}
            String checksum=HexFormat.of().formatHex(digest.digest());if(name.startsWith("documents/")&&!name.split("/")[2].equals(checksum))throw new IOException("Document checksum mismatch. Keep the existing data and repair the original before backing up.");
            zip.closeEntry();manifest.add(Map.of("path",name,"size",count,"sha256",checksum));
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
