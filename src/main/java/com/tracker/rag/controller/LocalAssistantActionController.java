package com.tracker.rag.controller;

import com.fasterxml.jackson.databind.*;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.dto.ConfirmationCard;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.TaskRepository;
import com.tracker.rag.service.CaptureService;
import com.tracker.rag.service.ai.AiClient;
import com.tracker.rag.service.desktop.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController @Profile("desktop") @RequestMapping("/api/assistant")
public class LocalAssistantActionController {
    private final AiClient ai; private final ObjectMapper mapper; private final TaskRepository tasks;
    private final CaptureService capture; private final LocalDataService data; private final String contract;
    private final LocalAiJobs jobs;
    public LocalAssistantActionController(AiClient ai, ObjectMapper mapper, TaskRepository tasks, CaptureService capture, LocalDataService data,LocalAiJobs jobs) throws java.io.IOException {
        this.ai=ai;this.mapper=mapper;this.tasks=tasks;this.capture=capture;this.data=data;
        this.jobs=jobs;
        try (var stream=new ClassPathResource("folio-ai-contract-v2.json").getInputStream()) { contract=mapper.readTree(stream).path("system").asText(); }
    }
    public record Request(String domain,String input,UUID selectedTaskId,Long expectedVersion,UUID requestId) {}
    public record Choice(UUID id,String title,String domain,long version) {}
    public record Draft(String type,String message,ConfirmationCard preview,List<Choice> choices) {}
    private Draft clarify(String message,String area) {
        var scopes=area.equals("ALL")?DomainScope.PUBLIC_DEFAULT:Set.of(DomainScope.parse(area));
        var choices=tasks.findByDomainIn(scopes).stream().filter(t->!t.isArchived()).limit(30)
                .map(t->new Choice(t.getId(),t.getTitle(),t.getDomain().name(),t.getVersion())).toList();
        return new Draft("clarify",message,null,choices);
    }
    @PostMapping("/cancel/{id}") public Map<String,Boolean> cancel(@PathVariable UUID id){jobs.cancel(id);return Map.of("cancelled",true);}
    @PostMapping("/actions") public Draft draft(@RequestBody Request request) throws Exception {
        try(var job=jobs.begin(request.requestId())){return draft(request,job);}
    }
    private Draft draft(Request request,LocalAiJobs.Scope job) throws Exception {
        String area=Objects.requireNonNull(request.domain(),"Select an area");
        var scopes=area.equals("ALL")?DomainScope.PUBLIC_DEFAULT:Set.of(DomainScope.parse(area));
        if(request.input()==null||request.input().isBlank()||request.input().length()>2000)throw new IllegalArgumentException("Enter a request of at most 2,000 characters");
        Task selected=null;
        if(request.selectedTaskId()!=null){
            selected=tasks.findByIdAndDomainIn(request.selectedTaskId(),scopes).filter(t->!t.isArchived()).orElseThrow(()->new IllegalArgumentException("Task not found in this area"));
            if(request.expectedVersion()==null||request.expectedVersion()!=selected.getVersion())LocalDataService.conflict();
        }
        LocalActionTimes times;
        try {times=LocalActionTimes.resolve(request.input(),data.zone(),Instant.now());}
        catch(DateTimeException error){return new Draft("clarify","Please provide a valid date and clock time.",null,List.of());}
        String context="Selected area="+area+". Workspace timezone="+data.zone()+". Current time="+ZonedDateTime.now(data.zone())+". Resolved dates="+times.dates()+". Explicit clock times="+times.clocks()+". ";
        context+=selected==null?"No task is selected. Edits require selection.":"Explicitly selected task="+mapper.writeValueAsString(Map.of("id",selected.getId(),"title",selected.getTitle(),"area",selected.getDomain().name()));
        JsonNode proposal=mapper.readTree(ai.generateJson(contract,context+"\nUser request: "+request.input()));
        job.check();
        if(proposal==null||!proposal.isObject())throw new IllegalArgumentException("Invalid assistant proposal");
        String action=proposal.path("action").asText();
        if(action.equals("clarify"))return request.input().toLowerCase(Locale.ROOT).matches("(?s).*\\b(edit|update|rename|reschedule|change|move|complete)\\b.*")
                ?clarify("Please specify the missing details or select the task to change.",area)
                :new Draft("clarify","Please specify the exact dates, times, or other missing details.",null,List.of());
        if(action.equals("unsupported"))return new Draft("unsupported","This request is not supported. Use the structured forms for other changes.",null,List.of());
        if(!Set.of("create_task","edit_task","create_event").contains(action))throw new IllegalArgumentException("The assistant did not return a supported action");
        boolean edit=action.equals("edit_task");
        if(selected!=null&&!edit)return clarify("The request did not produce an edit for the selected task. Please rephrase the change.",area);
        if(edit&&(selected==null||!selected.getId().toString().equals(proposal.path("recordId").asText())))return clarify("Select the task you want to change.",area);
        Set<String> fields=new HashSet<>(Set.of("action","area","title","priority","dueLocal","remindLocal"));
        if(edit)fields.add("recordId");if(action.equals("create_event")){fields=Set.of("action","area","title","startLocal","endLocal","remindLocal");}
        var iterator=proposal.fieldNames();while(iterator.hasNext())if(!fields.contains(iterator.next()))throw new IllegalArgumentException("Unexpected assistant proposal fields");
        DomainScope domain=edit?selected.getDomain():DomainScope.parse(proposal.path("area").asText());
        if(!scopes.contains(domain)||edit&&proposal.has("area")&&!domain.name().equals(proposal.path("area").asText()))throw new IllegalArgumentException("The proposal changes the selected area");
        Map<String,Object> payload=new LinkedHashMap<>();
        if(edit){payload.put("taskId",selected.getId().toString());payload.put("expectedVersion",selected.getVersion());payload.put("title",selected.getTitle());payload.put("priority",selected.getPriority().name());payload.put("status",selected.getStatus().name());payload.put("description",selected.getDescription());payload.put("dueDate",selected.getDueDate()==null?null:selected.getDueDate().toString());payload.put("remindAt",selected.getRemindAt()==null?null:selected.getRemindAt().toString());}
        for(String field:List.of("title","priority"))if(proposal.has(field)){if(!proposal.path(field).isTextual())throw new IllegalArgumentException("Invalid proposal field");payload.put(field,proposal.path(field).asText());}
        if(edit&&List.of("title","priority","dueLocal","remindLocal").stream().noneMatch(proposal::has))throw new IllegalArgumentException("The proposal contains no change");
        boolean reminder=request.input().toLowerCase(Locale.ROOT).matches("(?s).*\\b(remind|reminder|reminders|notify|notification|notifications|alert|alerts)\\b.*");
        if(reminder&&!proposal.has("remindLocal"))return new Draft("clarify","What exact date and time should the reminder use?",null,List.of());
        try {
            for(var mapping:Map.of("dueLocal","dueDate","remindLocal","remindAt","startLocal","startAt","endLocal","endAt").entrySet())if(proposal.has(mapping.getKey())){
                if(!proposal.path(mapping.getKey()).isTextual())throw new IllegalArgumentException("Provide an exact timestamp");
                payload.put(mapping.getValue(),times.instant(proposal.path(mapping.getKey()).asText()).toString());
            }
            if(action.equals("create_event")&&(times.dates().isEmpty()||times.clocks().size()<2))return new Draft("clarify","Please specify the event date, start time, and end time.",null,List.of());
        } catch(IllegalArgumentException error){return new Draft("clarify",error.getMessage(),null,List.of());}
        job.check();
        ConfirmationCard card=capture.createStructuredPreview(domain,action.equals("create_event")?"EVENT":edit?"TASK_UPDATE":"TASK",payload);
        return new Draft("proposal",card.summary(),card,List.of());
    }
}
