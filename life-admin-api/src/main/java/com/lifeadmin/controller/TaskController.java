package com.lifeadmin.controller;
import com.lifeadmin.dto.*;
import com.lifeadmin.service.TaskService;
import com.lifeadmin.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;
@RestController
@RequestMapping("/api")
public class TaskController {
    private final TaskService service;
    public TaskController(TaskService service) { this.service = service; }
    @GetMapping("/tasks/upcoming")
    public List<UpcomingTaskResponse> upcoming(@AuthenticationPrincipal AccountPrincipal principal) { return service.upcoming(principal.id()); }
    @GetMapping("/documents/{documentId}/tasks")
    public List<TaskResponse> list(@PathVariable UUID documentId, @AuthenticationPrincipal AccountPrincipal principal) {
        return service.list(principal.id(), documentId);
    }
    @PostMapping("/documents/{documentId}/tasks")
    public ResponseEntity<TaskResponse> create(@PathVariable UUID documentId, @Valid @RequestBody TaskRequest input, @AuthenticationPrincipal AccountPrincipal principal) {
        var task = service.create(principal.id(), documentId, input);
        return ResponseEntity.created(URI.create("/api/tasks/" + task.id())).body(task);
    }
    @PutMapping("/tasks/{taskId}")
    public TaskResponse update(@PathVariable UUID taskId, @Valid @RequestBody TaskRequest input, @AuthenticationPrincipal AccountPrincipal principal) {
        return service.update(principal.id(), taskId, input);
    }
    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> delete(@PathVariable UUID taskId, @AuthenticationPrincipal AccountPrincipal principal) {
        service.delete(principal.id(), taskId); return ResponseEntity.noContent().build();
    }
    @PatchMapping("/tasks/{taskId}/status")
    public TaskResponse status(@PathVariable UUID taskId, @Valid @RequestBody TaskStatusRequest input, @AuthenticationPrincipal AccountPrincipal principal) {
        return service.changeStatus(principal.id(), taskId, input.status());
    }
    @PostMapping("/documents/{documentId}/tasks/generate")
    public List<TaskResponse> generate(@PathVariable UUID documentId, @AuthenticationPrincipal AccountPrincipal principal, @Valid @RequestBody(required=false) TaskService.Selection selection) {
        return service.generate(principal.id(), documentId,selection);
    }
}
