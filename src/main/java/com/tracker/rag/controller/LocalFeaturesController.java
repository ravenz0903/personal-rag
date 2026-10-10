package com.tracker.rag.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tracker.rag.service.desktop.LocalDataService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;

@RestController @Profile("desktop") @RequestMapping("/api/local")
public class LocalFeaturesController {
    private final LocalDataService data;
    public LocalFeaturesController(LocalDataService data){this.data=data;}
    @GetMapping("/history") public Object history(@RequestParam(defaultValue="ALL") String domain){return data.history(domain);}
    @PostMapping("/history/{id}/undo") public Object undo(@PathVariable UUID id,@RequestParam String domain){data.undo(id,domain);return Map.of("ok",true);}
    @org.springframework.beans.factory.annotation.Autowired private com.tracker.rag.service.desktop.DesktopTaskService tasks;
    @org.springframework.beans.factory.annotation.Autowired private com.tracker.rag.service.desktop.LocalRecordService records;
    @GetMapping("/open/{kind}/{id}") public Object open(@PathVariable String kind,@PathVariable UUID id){
        if(kind.equals("task"))return tasks.open(id);
        String table=switch(kind){case "event"->"calendar_events";case "note"->"local_notes";default->throw new IllegalArgumentException("Unknown record");};
        var found=data.rows("SELECT * FROM "+table+" WHERE id=?",id);if(found.isEmpty())LocalDataService.missing();return kind.equals("note")?data.note(id,(String)found.getFirst().get("domain")):found.getFirst();
    }
    @GetMapping("/records/{kind}") public Object records(@PathVariable String kind){return records.records(kind);}
    @PutMapping("/records/{kind}/{id}") public Object record(@PathVariable String kind,@PathVariable UUID id,@RequestBody JsonNode input){return records.update(kind,id,input);}
    @PutMapping("/assessments/{id}") public Object assessment(@PathVariable UUID id,@RequestBody JsonNode input){return records.assessment(id,input);}
    @GetMapping("/progress") public Object progress(@RequestParam(defaultValue="ALL") String domain,@RequestParam(defaultValue="7") int days){return records.progress(domain,days);}
    @org.springframework.beans.factory.annotation.Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    @GetMapping("/export") public void export(jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException{
        var snapshot=records.export();response.setContentType("application/zip");response.setHeader(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=Folio-export.zip");
        try(var zip=new java.util.zip.ZipOutputStream(response.getOutputStream())){
            zip.putNextEntry(new java.util.zip.ZipEntry("records.json"));zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot));zip.closeEntry();
            for(var note:(List<Map<String,Object>>)snapshot.get("local_notes")){zip.putNextEntry(new java.util.zip.ZipEntry("notes/"+note.get("id")+".md"));zip.write(("# "+note.get("title")+"\n\n"+LocalDataService.markdown(mapper.readTree((String)note.get("content")))).getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
            zip.putNextEntry(new java.util.zip.ZipEntry("files-manifest.json"));zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot.get("document_records")));zip.closeEntry();
        }
    }
    @GetMapping("/tasks") public Object tasks(@RequestParam(defaultValue="ALL") String domain){return tasks.list(domain);}
    @PutMapping("/tasks/{id}") public Object task(@PathVariable UUID id,@RequestBody JsonNode input){return com.tracker.rag.service.desktop.DesktopTaskService.view(tasks.change(id,com.tracker.rag.domain.DomainScope.parse(LocalDataService.text(input,"domain",32)),input.path("version").asLong(-1),input.has("status")?input.path("status").asText():null,input.has("archived")?input.path("archived").asBoolean():null,input.has("dueDate")?java.time.Instant.parse(input.path("dueDate").asText()):null,input.has("dueDate")));}
    @GetMapping("/settings") public Map<String,Object> settings(){return data.preferences();}
    @PutMapping("/settings") public Map<String,Object> settings(@RequestBody JsonNode input){return data.preferences(input);}
    @GetMapping("/notes") public List<Map<String,Object>> notes(@RequestParam(defaultValue="ALL") String domain,@RequestParam(defaultValue="false") boolean archived){return data.notes(domain,archived);}
    @GetMapping("/notes/{id}") public Map<String,Object> note(@PathVariable UUID id,@RequestParam String domain){return data.note(id,domain);}
    @PostMapping("/notes") public Map<String,Object> note(@RequestBody JsonNode input){return data.saveNote(null,input);}
    @PutMapping("/notes/{id}") public Map<String,Object> note(@PathVariable UUID id,@RequestBody JsonNode input){return data.saveNote(id,input);}
    @GetMapping("/notes/{id}/markdown") public ResponseEntity<String> markdown(@PathVariable UUID id,@RequestParam String domain){var note=data.note(id,domain);return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8")).body("# "+note.get("title")+"\n\n"+LocalDataService.markdown((JsonNode)note.get("content")));}
    @GetMapping("/calendar") public List<Map<String,Object>> events(@RequestParam(defaultValue="ALL") String domain,@RequestParam(defaultValue="false") boolean archived){return data.events(domain,archived);}
    @PostMapping("/calendar") public Map<String,Object> event(@RequestBody JsonNode input){return data.saveEvent(null,input);}
    @PutMapping("/calendar/{id}") public Map<String,Object> event(@PathVariable UUID id,@RequestBody JsonNode input){return data.saveEvent(id,input);}
    @GetMapping("/search") public Map<String,Object> search(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="ALL") String domain,@RequestParam(defaultValue="0") int page){return data.search(q,domain,page);}
}
