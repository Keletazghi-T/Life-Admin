package com.lifeadmin.controller;
import com.lifeadmin.dto.DocumentAnalysisResponse;
import com.lifeadmin.service.DocumentAnalysisService;
import com.lifeadmin.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/documents/{documentId}")
public class DocumentAnalysisController {
    private final DocumentAnalysisService service;
    public DocumentAnalysisController(DocumentAnalysisService service) { this.service = service; }
    @PostMapping("/analyze")
    public DocumentAnalysisResponse analyze(@PathVariable UUID documentId, @AuthenticationPrincipal AccountPrincipal principal, @RequestBody(required=false) DocumentAnalysisService.Retry retry) {
        return service.analyze(principal.id(), documentId,retry);
    }
    public record Confirmation(@jakarta.validation.Valid @jakarta.validation.constraints.NotNull com.lifeadmin.dto.AnalysisContent content, java.time.Instant expectedVersion) {}
    @PutMapping("/analysis/confirm")
    public DocumentAnalysisResponse confirm(@PathVariable UUID documentId,@AuthenticationPrincipal AccountPrincipal principal,@jakarta.validation.Valid @RequestBody Confirmation input) {
        return service.confirm(principal.id(),documentId,input.content(),input.expectedVersion());
    }
    @GetMapping("/analysis-attempt")
    public org.springframework.http.ResponseEntity<DocumentAnalysisService.Attempt> attempt(@PathVariable UUID documentId,@AuthenticationPrincipal AccountPrincipal principal) {
        var attempt=service.attempt(principal.id(),documentId);
        return attempt==null?org.springframework.http.ResponseEntity.noContent().build():org.springframework.http.ResponseEntity.ok(attempt);
    }
    @GetMapping("/analysis")
    public DocumentAnalysisResponse get(@PathVariable UUID documentId, @AuthenticationPrincipal AccountPrincipal principal) {
        return service.get(principal.id(), documentId);
    }
}
