package com.lifeadmin.entity;

import com.lifeadmin.dto.AnalysisContent;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "document_analyses")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class DocumentAnalysis {
    @Id private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private AnalysisSource source;
    @Column(nullable = false, unique = true) private UUID documentId;
    @Column(nullable = false, length = 200) private String documentType;
    @Column(nullable = false, columnDefinition = "text") private String summary;
    private Boolean actionRequired;
    private LocalDate deadline;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> requiredDocuments;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> nextSteps;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> warnings;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    @Column(columnDefinition="text") private String meaning;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private List<String> evidence=List.of();
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private List<AnalysisContent.ImportantDate> importantDates=List.of();
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") private AnalysisContent confirmedContent;
    private Instant confirmedAt;
    public void confirm(AnalysisContent content) { confirmedContent=content; confirmedAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); updatedAt=confirmedAt; }
    public AnalysisContent suggestions() { return new AnalysisContent(documentType,summary,actionRequired,deadline,requiredDocuments,nextSteps,warnings,meaning,evidence,importantDates); }

    public DocumentAnalysis(UUID documentId, AnalysisContent result) {
        this(documentId, result, AnalysisSource.AI);
    }
    public DocumentAnalysis(UUID documentId, AnalysisContent result, AnalysisSource source) {
        this.source = source;
        this.id = UUID.randomUUID();
        this.documentId = documentId;
        this.documentType = result.documentType();
        this.summary = result.summary();
        this.actionRequired = result.actionRequired();
        this.deadline = result.deadline();
        this.requiredDocuments = List.copyOf(result.requiredDocuments());
        this.nextSteps = List.copyOf(result.nextSteps());
        this.warnings = List.copyOf(result.warnings());
        this.meaning=result.meaning(); this.evidence=result.evidence(); this.importantDates=result.importantDates();
        this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.updatedAt = createdAt;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
}
