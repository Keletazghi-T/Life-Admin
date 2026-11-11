package com.lifeadmin.config;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.*;
import org.springframework.web.cors.*;
import java.net.URI;
import java.util.List;
@Configuration
public class DeploymentConfiguration {
    @Bean public UrlBasedCorsConfigurationSource corsConfigurationSource(@Value("${app.frontend-url}") String frontend,Environment environment) {
        String origin=frontend.endsWith("/")?frontend.substring(0,frontend.length()-1):frontend;
        URI uri;
        try {uri=URI.create(origin);} catch(IllegalArgumentException e) {throw new IllegalArgumentException("FRONTEND_URL must be a single HTTP(S) origin.");}
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getPath().isEmpty() || !List.of("http","https").contains(uri.getScheme()))
            throw new IllegalArgumentException("FRONTEND_URL must be a single HTTP(S) origin.");
        if(environment.acceptsProfiles(Profiles.of("prod")) && !"https".equals(uri.getScheme()))
            throw new IllegalArgumentException("Production FRONTEND_URL must use HTTPS.");
        if(!"local".equals(environment.getProperty("app.storage-type","local")))
            throw new IllegalArgumentException("Unsupported DOCUMENT_STORAGE_TYPE. Only local storage is implemented; S3 is a future adapter.");
        CorsConfiguration config=new CorsConfiguration();config.setAllowedOrigins(List.of(origin));config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type","X-CSRF-TOKEN"));config.setMaxAge(600L);
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/api/**",config);return source;
    }
}
