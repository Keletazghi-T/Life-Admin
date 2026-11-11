package com.lifeadmin.controller;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
/** Minimal liveness check; deliberately does not expose dependencies or configuration. */
@RestController
public class HealthController {
    @GetMapping("/api/health") public Map<String,String> health() {return Map.of("status","UP");}
}
