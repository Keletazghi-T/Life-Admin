package com.lifeadmin.ai;

import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Synchronous attempt-local metadata only. Never accepts text, prompts, bodies or exception messages. */
public final class DocumentAnalysisDiagnostic implements AutoCloseable {
    public enum Stage { PRE_REQUEST, HTTP_REQUEST, HTTP_RESPONSE, RESPONSE_PARSE, RESPONSE_VALIDATION, PERSISTENCE }
    public enum ValidationReason { INVALID_RESULT_SHAPE, MISSING_REQUIRED_FIELD, INVALID_FIELD_TYPE, INVALID_ACTION_REQUIRED, INVALID_DEADLINE, INVALID_IMPORTANT_DATE, SOURCE_EXCERPT_MISMATCH, REQUIRED_VALUE_BLANK, FIELD_LENGTH_EXCEEDED, LIST_LIMIT_EXCEEDED, DATE_YEAR_OUT_OF_RANGE, MALFORMED_JSON, RESPONSE_TOO_LARGE, RESPONSE_NOT_COMPLETED, RESPONSE_REFUSED }
    public enum Field { documentType, summary, actionRequired, deadline, requiredDocuments, nextSteps, warnings, meaning, evidence, importantDates }
    private ValidationReason validationReason;
    private Field validationField;
    public void rejected(ValidationReason reason,Field field) { if(validationReason==null) {validationReason=reason;validationField=field;} }
    public static void rejection(ValidationReason reason,Field field) {var context=CURRENT.get();if(context!=null) context.rejected(reason,field);}
    private static final ThreadLocal<DocumentAnalysisDiagnostic> CURRENT=new ThreadLocal<>();
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger(DocumentAnalysisDiagnostic.class);
    private final DocumentAnalysisDiagnostic previous;
    private final UUID attemptId;
    private final Consumer<Map<String,Object>> sink;
    private Stage stage=Stage.PRE_REQUEST;
    private boolean extractionCompleted,httpRequestStarted,failed;
    private String model,requestId,errorCode,errorType,exceptionClass,rootCauseClass,exceptionCategory;
    private Integer httpStatus;
    private DocumentAnalysisDiagnostic(UUID attemptId,String model,Consumer<Map<String,Object>> sink,boolean bind) {
        this.attemptId=attemptId;this.model=safeModel(model);this.sink=sink;
        this.previous=bind?CURRENT.get():null;if(bind) CURRENT.set(this);
    }
    public static DocumentAnalysisDiagnostic open(UUID id,String model,Consumer<Map<String,Object>> sink) {
        var diagnostic=new DocumentAnalysisDiagnostic(id,model,sink,true);diagnostic.progress(Stage.PRE_REQUEST);return diagnostic;
    }
    static DocumentAnalysisDiagnostic current(String model,String apiKey) {
        var current=CURRENT.get();
        if(current==null) current=new DocumentAnalysisDiagnostic(UUID.randomUUID(),model,null,false);
        current.model=Objects.equals(model,apiKey)?null:safeModel(model);
        return current;
    }
    private static String safeModel(String value) {return value!=null && value.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,99}") && !value.startsWith("sk-")?value:null;}
    public void extracted() {extractionCompleted=true;progress(Stage.PRE_REQUEST);}
    void requestStarted() {httpRequestStarted=true;progress(Stage.HTTP_REQUEST);}
    void response(int status,String id) {
        httpStatus=status;requestId=id!=null && id.matches("req_[a-fA-F0-9]{32}")?id:null;progress(Stage.HTTP_RESPONSE);
    }
    // Values are allowlisted by the adapter before reaching this method.
    void error(String code,String type) {errorCode=code;errorType=type;}
    public void progress(Stage stage) {this.stage=stage;emit("PROGRESS");}
    public void success() {emit("SUCCEEDED");}
    public void failure(Throwable error) {failure(error,false);}
    void failure(Throwable error,boolean network) {
        if(failed) return;failed=true;
        var safe=SafeNetworkDiagnostic.from(error);
        exceptionClass=safe.exceptionClass();rootCauseClass=safe.rootCauseClass();
        exceptionCategory=network?safe.category():stage.name()+"_ERROR";
        emit("FAILED");
    }
    private void emit(String outcome) {
        var data=new LinkedHashMap<String,Object>();
        data.put("timestamp",Instant.now().toString());data.put("attemptId",attemptId.toString());data.put("stage",stage.name());data.put("outcome",outcome);
        data.put("extractionCompleted",extractionCompleted);data.put("httpRequestStarted",httpRequestStarted);
        data.put("httpStatus",httpStatus);data.put("errorCode",errorCode);data.put("errorType",errorType);data.put("requestId",requestId);data.put("model",model);
        data.put("exceptionClass",exceptionClass);data.put("rootCauseClass",rootCauseClass);data.put("exceptionCategory",exceptionCategory);
        data.put("validationReason",validationReason==null?null:validationReason.name());data.put("validationField",validationField==null?null:validationField.name());
        var snapshot=Collections.unmodifiableMap(data);
        if(failed) LOG.warn("document_analysis_diagnostic {}",snapshot); else LOG.info("document_analysis_diagnostic {}",snapshot);
        if(sink!=null) try {sink.accept(snapshot);} catch(RuntimeException failure) {
            // Diagnostic storage must not hide the original failure or change provider retry behavior.
            LOG.warn("document_analysis_diagnostic_storage_failure timestamp={} attemptId={} stage=PERSISTENCE exceptionClass={}",Instant.now(),attemptId,SafeNetworkDiagnostic.from(failure).exceptionClass());
        }
    }
    @Override public void close() {if(previous==null) CURRENT.remove();else CURRENT.set(previous);}
}
