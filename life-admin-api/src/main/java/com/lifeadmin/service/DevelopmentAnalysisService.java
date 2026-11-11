package com.lifeadmin.service;
import com.lifeadmin.config.DevelopmentFeatures;
import com.lifeadmin.dto.*;
import com.lifeadmin.entity.*;
import com.lifeadmin.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;
@Service
public class DevelopmentAnalysisService {
    private final DevelopmentFeatures features;
    private final DocumentRepository documents;
    private final DocumentAnalysisRepository analyses;
    public DevelopmentAnalysisService(DevelopmentFeatures features, DocumentRepository documents, DocumentAnalysisRepository analyses) {
        this.features = features; this.documents = documents; this.analyses = analyses;
    }
    @Transactional
    public DocumentAnalysisResponse load(UUID owner, UUID documentId) {
        if (!features.mockAnalysisEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found.");
        var document = documents.findOwnedForUpdate(documentId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found."));
        if (!"TestRecord.pdf".equalsIgnoreCase(document.getOriginalFileName()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Development sample analysis is only available for TestRecord.pdf.");
        var existing = analyses.findByDocumentId(documentId);
        if (existing.isPresent()) {
            if (existing.get().getSource() != AnalysisSource.DEVELOPMENT_MOCK)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An existing analysis cannot be replaced with development sample data.");
            return DocumentAnalysisResponse.from(existing.get());
        }
        var sample = new AnalysisContent("Government Notice",
                "This is sample development data showing how Life Admin will explain an important document.",
                true, LocalDate.of(2026, 10, 15), List.of("Proof of income", "Identification"),
                List.of("Find proof of income", "Upload proof of income", "Submit response"),
                List.of("Verify the deadline against the original document."));
        return DocumentAnalysisResponse.from(analyses.saveAndFlush(
                new DocumentAnalysis(documentId, sample, AnalysisSource.DEVELOPMENT_MOCK)));
    }
}
