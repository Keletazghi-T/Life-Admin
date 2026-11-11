package com.lifeadmin.controller;
import com.lifeadmin.config.DevelopmentFeatures;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/development")
public class DevelopmentCapabilitiesController {
    private final DevelopmentFeatures features;
    public DevelopmentCapabilitiesController(DevelopmentFeatures features) { this.features = features; }
    @GetMapping("/capabilities")
    public Map<String, Boolean> capabilities() { return Map.of("mockAnalysisEnabled", features.mockAnalysisEnabled()); }
}
