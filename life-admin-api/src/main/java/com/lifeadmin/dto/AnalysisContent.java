package com.lifeadmin.dto;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;

/** Provider-neutral structured result. Null actionRequired means unclear. */
public record AnalysisContent(
        @NotBlank @Size(max = 200) String documentType,
        @NotBlank @Size(max = 8000) String summary,
        Boolean actionRequired,
        LocalDate deadline,
        @NotNull @Size(max = 50) List<@NotBlank @Size(max = 1000) String> requiredDocuments,
        @NotNull @Size(max = 50) List<@NotBlank @Size(max = 1000) String> nextSteps,
        @NotNull @Size(max = 50) List<@NotBlank @Size(max = 1000) String> warnings,
        @Size(max=8000) String meaning,
        @NotNull @Size(max=20) List<@NotBlank @Size(max=1000) String> evidence,
        @NotNull @Size(max=20) List<@NotNull @Valid ImportantDate> importantDates) {
    public record ImportantDate(@NotBlank @Size(max=200) String label, LocalDate date,
        @NotBlank @Size(max=1000) String evidence) {}
    public AnalysisContent(String documentType,String summary,Boolean actionRequired,LocalDate deadline,
        List<String> requiredDocuments,List<String> nextSteps,List<String> warnings) {
        this(documentType,summary,actionRequired,deadline,requiredDocuments,nextSteps,warnings,null,List.of(),List.of());
    }
}
