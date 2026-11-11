package com.lifeadmin.controller;
import com.lifeadmin.dto.*;
import com.lifeadmin.service.ReminderService;
import com.lifeadmin.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;
@RestController @RequestMapping("/api")
public class ReminderController {
    private final ReminderService service;
    public ReminderController(ReminderService service) { this.service=service; }
    @GetMapping("/tasks/{taskId}/reminders") public List<ReminderResponse> list(@PathVariable UUID taskId,@AuthenticationPrincipal AccountPrincipal principal) { return service.list(principal.id(),taskId); }
    @PostMapping("/tasks/{taskId}/reminders") public ResponseEntity<ReminderResponse> create(@PathVariable UUID taskId,@Valid @RequestBody ReminderRequest input,@AuthenticationPrincipal AccountPrincipal principal) {
        var r=service.create(principal.id(),taskId,input); return ResponseEntity.created(URI.create("/api/reminders/"+r.id())).body(r);
    }
    @PutMapping("/reminders/{id}") public ReminderResponse update(@PathVariable UUID id,@Valid @RequestBody ReminderRequest input,@AuthenticationPrincipal AccountPrincipal principal) { return service.update(principal.id(),id,input); }
    @PatchMapping("/reminders/{id}/status") public ReminderResponse status(@PathVariable UUID id,@Valid @RequestBody ReminderStatusRequest input,@AuthenticationPrincipal AccountPrincipal principal) { return service.status(principal.id(),id,input.status()); }
    @DeleteMapping("/reminders/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal principal) { service.delete(principal.id(),id); return ResponseEntity.noContent().build(); }
}
