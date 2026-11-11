package com.lifeadmin.service;
import com.lifeadmin.dto.*;
import com.lifeadmin.entity.*;
import com.lifeadmin.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@Service
public class TaskService {
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final DocumentRepository documents;
    private final TaskRepository tasks;
    private final DocumentAnalysisRepository analyses;
    private final TaskGenerationRepository generations;
    private final com.lifeadmin.config.DevelopmentFeatures development;
    public TaskService(DocumentRepository documents, TaskRepository tasks, DocumentAnalysisRepository analyses,
            TaskGenerationRepository generations, com.lifeadmin.config.DevelopmentFeatures development, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc=jdbc; this.documents = documents; this.tasks = tasks; this.analyses = analyses; this.generations = generations; this.development = development;
    }
    @Transactional(readOnly = true)
    public List<UpcomingTaskResponse> upcoming(UUID owner) {
        return tasks.upcoming(owner, org.springframework.data.domain.PageRequest.of(0,50));
    }
    @Transactional(readOnly = true)
    public List<TaskResponse> list(UUID owner, UUID documentId) {
        ownedDocument(owner, documentId);
        return listOwned(owner, documentId);
    }
    @Transactional
    public TaskResponse create(UUID owner, UUID documentId, TaskRequest input) {
        return TaskResponse.from(tasks.save(new Task(ownedDocument(owner, documentId), input, false)));
    }
    @Transactional
    public TaskResponse update(UUID owner, UUID taskId, TaskRequest input) {
        Task task = ownedTask(owner, taskId); task.update(input); return TaskResponse.from(task);
    }
    @Transactional
    public TaskResponse changeStatus(UUID owner, UUID taskId, TaskStatus status) {
        Task task = ownedTask(owner, taskId); task.changeStatus(status); return TaskResponse.from(task);
    }
    @Transactional
    public void delete(UUID owner, UUID taskId) { tasks.delete(ownedTask(owner, taskId)); }
    public record Selection(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max=50) List<@jakarta.validation.constraints.NotNull Integer> steps,
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max=20) List<@jakarta.validation.constraints.NotNull Integer> dates,
        boolean includeDeadline, java.time.Instant confirmedAt) {}
    @Transactional
    public List<TaskResponse> generate(UUID owner,UUID documentId) { return generate(owner,documentId,null); }
    @Transactional
    public List<TaskResponse> generate(UUID owner,UUID documentId,Selection selection) {
        Document document=documents.findOwnedForUpdate(documentId,owner).orElseThrow(()->missing("Document"));
        var analysis=analyses.findByDocumentId(documentId).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Confirm document details before creating tasks."));
        if(analysis.getSource()==AnalysisSource.DEVELOPMENT_MOCK && !development.mockAnalysisEnabled()) throw missing("Analysis");
        if(analysis.getConfirmedContent()==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Review and confirm the analysis before creating tasks.");
        if(selection!=null && !Objects.equals(selection.confirmedAt(),analysis.getConfirmedAt())) throw new ResponseStatusException(HttpStatus.CONFLICT,"The confirmed details changed. Refresh before selecting actions.");
        // Preserve historical generation history, including tasks subsequently edited or deleted.
        if(generations.existsById(analysis.getId())) return listOwned(owner,documentId);
        var content=analysis.getConfirmedContent();
        var requested=new ArrayList<TaskRequest>();
        List<Integer> steps=selection==null?java.util.stream.IntStream.range(0,content.nextSteps().size()).boxed().toList():selection.steps();
        for(Integer index:steps) {
            if(index==null || index<0 || index>=content.nextSteps().size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid selected action.");
            requested.add(new TaskRequest(content.nextSteps().get(index),null,selection==null?content.deadline():null));
        }
        if(selection!=null) {
            for(Integer index:selection.dates()) {
                if(index==null || index<0 || index>=content.importantDates().size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid selected date.");
                var date=content.importantDates().get(index);
                if(date.date()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Confirm a specific date before creating a dated task.");
                requested.add(new TaskRequest(date.label(),null,date.date()));
            }
            if(selection.includeDeadline()) {
                if(content.deadline()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No confirmed deadline.");
                requested.add(new TaskRequest("Review document deadline",null,content.deadline()));
            }
        }
        for(var task:requested) {
            String key;
            try {key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((task.title().strip()+"\n"+Objects.toString(task.dueDate(),"")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
            catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException("SHA-256 unavailable");}
            int inserted=jdbc.update("insert into document_action_generations(analysis_id,action_key) values (?,?) on conflict do nothing",analysis.getId(),key);
            if(inserted==1) tasks.save(new Task(document,task,analysis.getSource()==AnalysisSource.DEVELOPMENT_MOCK));
        }
        if(selection==null) generations.save(new TaskGeneration(analysis.getId()));
        return listOwned(owner,documentId);
    }
    private List<TaskResponse> listOwned(UUID owner, UUID documentId) {
        return tasks.findByDocumentIdAndDocumentUserIdOrderByCreatedAtAscIdAsc(documentId, owner)
                .stream().map(TaskResponse::from).toList();
    }
    private Document ownedDocument(UUID owner, UUID documentId) {
        return documents.findActiveOwned(documentId, owner).orElseThrow(() -> missing("Document"));
    }
    private Task ownedTask(UUID owner, UUID taskId) {
        return tasks.findOwnedForUpdate(taskId, owner).orElseThrow(() -> missing("Task"));
    }
    private ResponseStatusException missing(String resource) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, resource + " not found.");
    }
}
