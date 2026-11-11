package com.lifeadmin.config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.*;
import org.springframework.stereotype.Component;
@Component
public class DevelopmentFeatures {
    private final boolean mockAnalysisEnabled;
    public DevelopmentFeatures(Environment environment,
            @Value("${app.development.mock-analysis-enabled:false}") boolean enabled) {
        mockAnalysisEnabled = enabled && environment.acceptsProfiles(Profiles.of("dev & !prod"));
    }
    public boolean mockAnalysisEnabled() { return mockAnalysisEnabled; }
}
