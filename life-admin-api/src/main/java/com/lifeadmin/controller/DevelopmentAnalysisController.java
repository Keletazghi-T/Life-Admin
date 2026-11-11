package com.lifeadmin.controller;
import com.lifeadmin.dto.DocumentAnalysisResponse;
import com.lifeadmin.service.DevelopmentAnalysisService;
import com.lifeadmin.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@Profile("dev & !prod")
@ConditionalOnProperty(name = "app.development.mock-analysis-enabled", havingValue = "true")
@RequestMapping("/api/development/documents/{documentId}")
public class DevelopmentAnalysisController {
    private final DevelopmentAnalysisService service;
    public DevelopmentAnalysisController(DevelopmentAnalysisService service) { this.service = service; }
    @PostMapping("/mock-analysis")
    public DocumentAnalysisResponse load(@PathVariable UUID documentId, @AuthenticationPrincipal AccountPrincipal principal) {
        return service.load(principal.id(), documentId);
    }
}
