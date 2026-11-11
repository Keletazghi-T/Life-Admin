package com.lifeadmin.dto;
import com.lifeadmin.entity.DocumentAnalysis;
import com.lifeadmin.entity.AnalysisSource;
import java.time.*;
import java.util.*;
public record DocumentAnalysisResponse(UUID id, UUID documentId, String documentType, String summary,
        Boolean actionRequired, LocalDate deadline, List<String> requiredDocuments,
        List<String> nextSteps, List<String> warnings, Instant createdAt, Instant updatedAt, AnalysisSource source, String meaning, List<String> evidence, List<AnalysisContent.ImportantDate> importantDates, AnalysisContent confirmedContent, Instant confirmedAt) {
    public static DocumentAnalysisResponse from(DocumentAnalysis analysis) {
        return new DocumentAnalysisResponse(analysis.getId(), analysis.getDocumentId(), analysis.getDocumentType(),
                analysis.getSummary(), analysis.getActionRequired(), analysis.getDeadline(),
                analysis.getRequiredDocuments(), analysis.getNextSteps(), analysis.getWarnings(),
                analysis.getCreatedAt(), analysis.getUpdatedAt(), analysis.getSource(), analysis.getMeaning(), analysis.getEvidence(), analysis.getImportantDates(), analysis.getConfirmedContent(), analysis.getConfirmedAt());
    }
}
