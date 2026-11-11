package com.lifeadmin.service;

import com.lifeadmin.ai.DocumentAnalysisProvider;
import com.lifeadmin.ai.DocumentAnalysisDiagnostic;
import static com.lifeadmin.ai.DocumentAnalysisDiagnostic.Stage.*;
import static com.lifeadmin.ai.DocumentAnalysisDiagnostic.ValidationReason.*;
import com.lifeadmin.ai.DocumentAnalysisDiagnostic.Field;
import com.lifeadmin.dto.*;
import com.lifeadmin.entity.*;
import com.lifeadmin.repository.*;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@Service
public class DocumentAnalysisService {
    private final DocumentRepository documents;
    private final DocumentAnalysisRepository analyses;
    private final DocumentStorage storage;
    private final PdfTextExtractor extractor;
    private final DocumentAnalysisProvider provider;
    private final Validator validator;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Value("${app.ai.openai.model:gpt-4o-mini}") private String diagnosticModel;
    private final com.fasterxml.jackson.databind.ObjectMapper diagnosticMapper=new com.fasterxml.jackson.databind.ObjectMapper();
    public record Attempt(UUID attemptId,String status,Instant startedAt,String message) {}
    public record Retry(UUID expectedAttemptId,boolean retryConfirmed) {}
    private record Claim(Document document,UUID attemptId,DocumentAnalysisResponse saved) {}
    public DocumentAnalysisService(DocumentRepository documents,DocumentAnalysisRepository analyses,DocumentStorage storage,
        PdfTextExtractor extractor,DocumentAnalysisProvider provider,Validator validator,JdbcTemplate jdbc,PlatformTransactionManager transactions) {
        this.documents=documents;this.analyses=analyses;this.storage=storage;this.extractor=extractor;this.provider=provider;this.validator=validator;this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);
    }
    public DocumentAnalysisResponse analyze(UUID owner,UUID documentId) { return analyze(owner,documentId,null); }
    public DocumentAnalysisResponse analyze(UUID owner,UUID documentId,Retry retry) {
        Claim claim=tx.execute(status->{
            Document document=locked(owner,documentId);
            var saved=analyses.findByDocumentId(documentId);
            if(saved.isPresent()) return new Claim(document,null,DocumentAnalysisResponse.from(saved.get()));
            Attempt previous=attemptRow(documentId);
            if(previous!=null) {
                if("PROCESSING".equals(previous.status())) throw conflict("This document is already being analyzed. You can refresh to check its status or enter details manually.");
                if(retry==null || !retry.retryConfirmed() || !previous.attemptId().equals(retry.expectedAttemptId())) throw conflict("Confirm another analysis attempt before trying again.");
                if(previous.startedAt().plusSeconds(30).isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Please wait 30 seconds before another attempt.");
            }
            UUID attempt=UUID.randomUUID();
            jdbc.update("insert into document_analysis_attempts(document_id,attempt_id,status,started_at) values (?,?,'PROCESSING',now()) on conflict(document_id) do update set attempt_id=excluded.attempt_id,status='PROCESSING',started_at=now(),finished_at=null,message=null,diagnostics=null",documentId,attempt);
            return new Claim(document,attempt,null);
        });
        if(claim.saved()!=null) return claim.saved();
        try(var diagnostic=DocumentAnalysisDiagnostic.open(claim.attemptId(),diagnosticModel,snapshot->{
            try {
                String safeJson=diagnosticMapper.writeValueAsString(snapshot);
                tx.executeWithoutResult(status->jdbc.update("update document_analysis_attempts set diagnostics=?::jsonb where document_id=? and attempt_id=?",safeJson,documentId,claim.attemptId()));
            } catch(com.fasterxml.jackson.core.JsonProcessingException error) {throw new IllegalStateException("Diagnostic encoding failed");}
        })) {
        try {
            String text=extractor.extract(storage.read(claim.document().getStoredFileName()));
            diagnostic.extracted();
            if(text.codePoints().filter(Character::isLetterOrDigit).count()<10) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"There isn't enough readable text to analyze this document. Review it manually. Scanned PDFs need OCR, which is not supported yet.");
            AnalysisContent result=provider.analyze(text);
            diagnostic.progress(RESPONSE_VALIDATION);
            try { validate(result); } catch(ResponseStatusException invalid) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"The analysis was incomplete. Please review the document manually."); }
            // Validate the original result first: filtering must never conceal invalid fields or limits.
            AnalysisContent grounded=DocumentEvidenceFilter.filter(result,text);
            diagnostic.progress(PERSISTENCE);
            var response=tx.execute(status->{
                locked(owner,documentId);
                // A user may confirm manual details while an external request is in progress.
                var saved=analyses.findByDocumentId(documentId).orElseGet(()->analyses.saveAndFlush(new DocumentAnalysis(documentId,grounded)));
                jdbc.update("update document_analysis_attempts set status='SUCCEEDED',finished_at=now() where document_id=? and attempt_id=?",documentId,claim.attemptId());
                return DocumentAnalysisResponse.from(saved);
            });
            diagnostic.success();return response;
        } catch(RuntimeException e) {
            diagnostic.failure(e);
            String message=e instanceof ResponseStatusException r && r.getStatusCode().value()==422?r.getReason():"We couldn't analyze this document. You can review the original and enter details manually.";
            if(e instanceof ResponseStatusException r && r.getStatusCode().value()==503) message="AI analysis is not configured. You can enter the details manually.";
            final String safeMessage=message;
            try {tx.executeWithoutResult(status->jdbc.update("update document_analysis_attempts set status='FAILED',finished_at=now(),message=? where document_id=? and attempt_id=?",safeMessage,documentId,claim.attemptId()));}
            catch(RuntimeException persistenceFailure) { /* Original safe diagnostic has already been logged; preserve the friendly response. */ }
            if(e instanceof ResponseStatusException r) throw new ResponseStatusException(r.getStatusCode(),message);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,message);
        }
        }
    }
    public DocumentAnalysisResponse confirm(UUID owner,UUID documentId,AnalysisContent content,Instant expectedVersion) {
        return tx.execute(status->{
            locked(owner,documentId);validate(content);
            var saved=analyses.findByDocumentId(documentId);
            if(saved.isPresent() && !saved.get().getUpdatedAt().equals(expectedVersion)) throw conflict("This analysis changed. Refresh before confirming your edits.");
            if(saved.isEmpty() && expectedVersion!=null) throw conflict("Refresh before confirming your edits.");
            var analysis=saved.orElseGet(()->new DocumentAnalysis(documentId,content,AnalysisSource.MANUAL));
            analysis.confirm(content);return DocumentAnalysisResponse.from(analyses.saveAndFlush(analysis));
        });
    }
    public Attempt attempt(UUID owner,UUID documentId) { owned(owner,documentId);return attemptRow(documentId); }
    private Attempt attemptRow(UUID documentId) {
        return jdbc.query("select attempt_id,status,started_at,message from document_analysis_attempts where document_id=?",(rs,i)->new Attempt(rs.getObject(1,UUID.class),rs.getString(2),rs.getTimestamp(3).toInstant(),rs.getString(4)),documentId).stream().findFirst().orElse(null);
    }
    public DocumentAnalysisResponse get(UUID owner,UUID documentId) { owned(owner,documentId);return analyses.findByDocumentId(documentId).map(DocumentAnalysisResponse::from).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"This document has not been analyzed yet.")); }
    private void validate(AnalysisContent result) {
        if(result==null) {DocumentAnalysisDiagnostic.rejection(INVALID_RESULT_SHAPE,null);throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Please review the analysis fields and their lengths.");}
        var violations=validator.validate(result);
        if(!violations.isEmpty()) {
            // Only constant constraint classifications and allowlisted root fields are retained.
            var violation=violations.stream().sorted(java.util.Comparator.comparing(v->v.getPropertyPath().toString()+v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())).findFirst().orElseThrow();
            Field field=null;
            var nodes=violation.getPropertyPath().iterator();
            if(nodes.hasNext()) try {field=Field.valueOf(nodes.next().getName());} catch(IllegalArgumentException | NullPointerException ignored) { }
            var annotation=violation.getConstraintDescriptor().getAnnotation().annotationType();
            var reason=annotation==jakarta.validation.constraints.NotBlank.class?REQUIRED_VALUE_BLANK:
                annotation==jakarta.validation.constraints.Size.class?(violation.getInvalidValue() instanceof java.util.Collection<?>?LIST_LIMIT_EXCEEDED:FIELD_LENGTH_EXCEEDED):INVALID_FIELD_TYPE;
            DocumentAnalysisDiagnostic.rejection(reason,field);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Please review the analysis fields and their lengths.");
        }
        if(result.deadline()!=null && (result.deadline().getYear()<1 || result.deadline().getYear()>9999)) {DocumentAnalysisDiagnostic.rejection(DATE_YEAR_OUT_OF_RANGE,Field.deadline);throw conflict("Invalid deadline.");}
        for(var date:result.importantDates()) if(date.date()!=null && (date.date().getYear()<1 || date.date().getYear()>9999)) {DocumentAnalysisDiagnostic.rejection(DATE_YEAR_OUT_OF_RANGE,Field.importantDates);throw conflict("Invalid date.");}
    }
    private Document owned(UUID owner,UUID id) {return documents.findActiveOwned(id,owner).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Document not found."));}
    private Document locked(UUID owner,UUID id) {return documents.findOwnedForUpdate(id,owner).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Document not found."));}
    private ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
