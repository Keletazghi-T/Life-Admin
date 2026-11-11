package com.lifeadmin.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiDocumentAnalysisProviderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private MockRestServiceServer server;
    private OpenAiDocumentAnalysisProvider provider;
    private RestClient client;
    private static final String RESULT = """
            {"documentType":"Notice","summary":"A letter for review.","actionRequired":null,
            "deadline":null,"requiredDocuments":[],"nextSteps":[],"warnings":[],"meaning":null,"evidence":[],"importantDates":[]}
            """;
    @BeforeEach void setup() throws Exception {
        var builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        provider = new OpenAiDocumentAnalysisProvider(client, mapper, "test-key-not-a-secret", "test-model");
    }
    private String response(String result) throws Exception {
        return mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(
                Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", result))))));
    }
    @Test void sendsTextWithStrictSchemaAndPreservesUnknownValues() throws Exception {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key-not-a-secret"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.input[1].content").value("A sample letter."))
                .andExpect(jsonPath("$.text.format.type").value("json_schema"))
                .andExpect(jsonPath("$.text.format.strict").value(true))
                .andRespond(withSuccess(response(RESULT), MediaType.APPLICATION_JSON));
        var result = provider.analyze("A sample letter.");
        assertThat(result.actionRequired()).isNull();
        assertThat(result.deadline()).isNull();
        assertThat(result.documentType()).isEqualTo("Notice");
        server.verify();
    }
    @Test void absentKeyDoesNotMakeAnyNetworkRequest() throws Exception {
        var disabled = new OpenAiDocumentAnalysisProvider(client, mapper, "  ", "test-model");
        assertThatThrownBy(() -> disabled.analyze("sample"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(503))
                .hasMessageContaining("AI analysis is not configured");
        server.verify();
    }
    @ParameterizedTest
    @ValueSource(strings = {"not json", "{}", "null", "{\"status\":\"incomplete\",\"output\":[]}",
            "{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"refusal\",\"refusal\":\"Refused\"}]}]}"})
    void rejectsMalformedIncompleteOrRefusedResponses(String body) {
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.analyze("sample"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
        server.verify();
    }
    @Test void rejectsMissingFieldsInvalidDatesAndTypeCoercion() throws Exception {
        for (String result : List.of(RESULT + " {}",
                RESULT.replace("\"deadline\":null", "\"deadline\":\"2026-10-15T01:00:00\""),
                RESULT.replace("\"warnings\":[]", "\"warnings\":[123]"),
                RESULT.replace("\"deadline\":null,", ""),
                RESULT.replace("\"deadline\":null", "\"deadline\":\"not-a-date\""),
                RESULT.replace("\"actionRequired\":null", "\"actionRequired\":\"true\""))) {
            server.reset();
            server.expect(requestTo("https://api.openai.com/v1/responses"))
                    .andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> provider.analyze("sample"))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
            server.verify();
        }
    }
    @Test void providerErrorsDoNotExposeResponseBodies() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private provider response"));
        assertThatThrownBy(() -> provider.analyze("sample")).hasMessageNotContaining("private provider response")
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
    }
    @Test void networkFailureHasSafeRetryMessage() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(request -> { throw new ResourceAccessException("private diagnostic"); });
        assertThatThrownBy(() -> provider.analyze("sample")).hasMessageNotContaining("private diagnostic")
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(504));
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,404,429,500})
    void safeHttpDiagnosticsRetainOnlyAllowlistedMetadata(int status) {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(DocumentAnalysisDiagnostic.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withStatus(HttpStatus.valueOf(status))
                .header("x-request-id","req_0123456789abcdef0123456789abcdef")
                .body("{\"error\":{\"code\":\"insufficient_quota\",\"type\":\"invalid_request_error\",\"message\":\"PRIVATE DOCUMENT test-key-not-a-secret\"}}"));
            assertThatThrownBy(()->provider.analyze("PRIVATE DOCUMENT")).isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("PRIVATE DOCUMENT");
            var failures=appender.list.stream().filter(e->e.getFormattedMessage().contains("outcome=FAILED")).toList();assertThat(failures).hasSize(1);var event=failures.get(0);String message=event.getFormattedMessage();
            assertThat(message).contains("stage=HTTP_RESPONSE","httpStatus="+status,"errorCode=insufficient_quota","requestId=req_0123456789abcdef0123456789abcdef","model=test-model").doesNotContain("PRIVATE DOCUMENT","test-key-not-a-secret","Authorization");
            assertThat(event.getThrowableProxy()).isNull();server.verify();
        } finally {logger.detachAppender(appender);appender.stop();}
    }
    @Test void untrustedErrorMetadataAndNetworkMessageNeverAppearInLogs() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(DocumentAnalysisDiagnostic.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(request->{throw new ResourceAccessException("PRIVATE TEXT",new java.net.SocketTimeoutException("test-key-not-a-secret"));});
            assertThatThrownBy(()->provider.analyze("PRIVATE DOCUMENT")).isInstanceOf(ResponseStatusException.class);
            assertThat(appender.list.getLast().getFormattedMessage()).contains("stage=HTTP_REQUEST","SocketTimeoutException").doesNotContain("PRIVATE TEXT","PRIVATE DOCUMENT","test-key-not-a-secret");
            server.verify();server.reset();
            server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withStatus(HttpStatus.BAD_REQUEST).header("x-request-id","PRIVATE ID").body("{\"error\":{\"code\":\"PRIVATE CODE\",\"type\":\"PRIVATE TYPE\"}}"));
            assertThatThrownBy(()->provider.analyze("text")).isInstanceOf(ResponseStatusException.class);
            assertThat(appender.list.getLast().getFormattedMessage()).contains("errorCode=null","errorType=null","requestId=null").doesNotContain("PRIVATE");server.verify();
        } finally {logger.detachAppender(appender);appender.stop();}
    }
    @Test void onlyExactSourceExcerptsCanBePresentedAsDocumentEvidence() throws Exception {
        String supplied="Respond by October 15, 2026.";
        String valid=RESULT.replace("\"evidence\":[]","\"evidence\":[\"Respond by October 15, 2026.\"]").replace("\"importantDates\":[]","\"importantDates\":[{\"label\":\"Response deadline\",\"date\":\"2026-10-15\",\"evidence\":\"Respond by October 15, 2026.\"}]");
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess(response(valid),MediaType.APPLICATION_JSON));
        assertThat(com.lifeadmin.service.DocumentEvidenceFilter.filter(provider.analyze(supplied),supplied).importantDates().get(0).date()).isEqualTo(java.time.LocalDate.of(2026,10,15));server.verify();server.reset();
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess(response(valid),MediaType.APPLICATION_JSON));
        var filtered=com.lifeadmin.service.DocumentEvidenceFilter.filter(provider.analyze("Unrelated text"),"Unrelated text");
        assertThat(filtered.evidence()).isEmpty();assertThat(filtered.importantDates()).isEmpty();server.verify();
    }

    @Test void successRecordsCorrelatedStagesAndSafeHttpMetadata() throws Exception {
        var snapshots=new ArrayList<Map<String,Object>>();UUID attempt=UUID.randomUUID();
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess(response(RESULT),MediaType.APPLICATION_JSON).header("x-request-id","req_0123456789abcdef0123456789abcdef"));
        try(var diagnostic=DocumentAnalysisDiagnostic.open(attempt,"test-model",snapshots::add)) {
            diagnostic.extracted();provider.analyze("A sample letter.");
        }
        assertThat(snapshots).extracting(m->m.get("stage")).containsSubsequence("PRE_REQUEST","HTTP_REQUEST","HTTP_RESPONSE","RESPONSE_PARSE","RESPONSE_VALIDATION");
        var last=snapshots.getLast();assertThat(last).containsEntry("attemptId",attempt.toString()).containsEntry("httpStatus",200).containsEntry("extractionCompleted",true).containsEntry("httpRequestStarted",true).containsEntry("requestId","req_0123456789abcdef0123456789abcdef");
        for(var snapshot:snapshots) {assertThat(java.time.Instant.parse((String)snapshot.get("timestamp"))).isNotNull();assertThat(snapshot.toString()).doesNotContain("A sample letter.","test-key-not-a-secret","Authorization","summary");}
        server.verify();
    }
    @Test void malformedJsonAndSchemaFailuresHaveDistinctStages() throws Exception {
        for(var entry:Map.of("not JSON","RESPONSE_PARSE",response(RESULT.replace("\"warnings\":[]","\"warnings\":[123]")),"RESPONSE_VALIDATION").entrySet()) {
            var snapshots=new ArrayList<Map<String,Object>>();server.reset();
            server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess(entry.getKey(),MediaType.APPLICATION_JSON));
            try(var diagnostic=DocumentAnalysisDiagnostic.open(UUID.randomUUID(),"test-model",snapshots::add)) {
                diagnostic.extracted();assertThatThrownBy(()->provider.analyze("sample document")).isInstanceOf(ResponseStatusException.class);
            }
            assertThat(snapshots.getLast()).containsEntry("stage",entry.getValue()).containsEntry("outcome","FAILED").containsEntry("httpStatus",200);
            assertThat(snapshots.toString()).doesNotContain("invented private quote","sample document","test-key-not-a-secret");server.verify();
        }
    }
    @Test void missingKeyIsPreRequestAndContextDoesNotLeakBetweenAttempts() throws Exception {
        var snapshots=new ArrayList<Map<String,Object>>();UUID first=UUID.randomUUID();
        try(var diagnostic=DocumentAnalysisDiagnostic.open(first,"test-model",snapshots::add)) {
            diagnostic.extracted();var disabled=new OpenAiDocumentAnalysisProvider(client,mapper,"","test-model");
            assertThatThrownBy(()->disabled.analyze("private contents")).isInstanceOf(ResponseStatusException.class);
        }
        assertThat(snapshots.getLast()).containsEntry("stage","PRE_REQUEST").containsEntry("httpRequestStarted",false).containsEntry("extractionCompleted",true);
        var next=new ArrayList<Map<String,Object>>();UUID second=UUID.randomUUID();
        try(var diagnostic=DocumentAnalysisDiagnostic.open(second,"test-model",next::add)) {diagnostic.extracted();}
        assertThat(next.getLast()).containsEntry("attemptId",second.toString()).containsEntry("outcome","PROGRESS").containsEntry("httpStatus",null);
        server.verify();
    }
    @Test void diagnosticStorageFailureCannotChangeProviderBehaviorOrExposeExceptionMessage() throws Exception {
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess(response(RESULT),MediaType.APPLICATION_JSON));
        try(var diagnostic=DocumentAnalysisDiagnostic.open(UUID.randomUUID(),"test-model",snapshot->{throw new IllegalStateException("PRIVATE DATABASE DETAILS");})) {
            diagnostic.extracted();assertThat(provider.analyze("sample").documentType()).isEqualTo("Notice");
        }
        server.verify();
    }

    @Test void validationReasonsAreAllowlistedAndNeverIncludeRejectedValues() throws Exception {
        record Case(String output,String reason,String field) {}
        var cases=List.of(
            new Case("[]","INVALID_RESULT_SHAPE",null),
            new Case(RESULT.replace("\"deadline\":null,",""),"MISSING_REQUIRED_FIELD","deadline"),
            new Case(RESULT.replace("\"summary\":\"A letter for review.\"","\"summary\":123"),"INVALID_FIELD_TYPE","summary"),
            new Case(RESULT.replace("\"actionRequired\":null","\"actionRequired\":\"PRIVATE VALUE\""),"INVALID_ACTION_REQUIRED","actionRequired"),
            new Case(RESULT.replace("\"deadline\":null","\"deadline\":\"PRIVATE DATE\""),"INVALID_DEADLINE","deadline"),
            new Case(RESULT.replace("\"deadline\":null","\"deadline\":\"2026-02-30\""),"INVALID_DEADLINE","deadline"),
            new Case(RESULT.replace("\"importantDates\":[]","\"importantDates\":[{}]"),"INVALID_IMPORTANT_DATE","importantDates"),
            new Case("not JSON","MALFORMED_JSON",null));
        for(var item:cases) {
            server.reset();var snapshots=new ArrayList<Map<String,Object>>();
            server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess(response(item.output()),MediaType.APPLICATION_JSON));
            try(var diagnostic=DocumentAnalysisDiagnostic.open(UUID.randomUUID(),"test-model",snapshots::add)) {
                diagnostic.extracted();assertThatThrownBy(()->provider.analyze("PRIVATE DOCUMENT")).isInstanceOf(ResponseStatusException.class);
            }
            assertThat(snapshots.getLast()).containsEntry("validationReason",item.reason()).containsEntry("validationField",item.field());
            assertThat(snapshots.toString()).doesNotContain("PRIVATE","test-key-not-a-secret","2026-02-30","Authorization");server.verify();
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"Please respond by October 15, 2026.",
        "Please\nrespond by\r\nOctober 15, 2026.",
        "  Please   respond\tby October 15, 2026.  ",
        "Please\u00a0respond\u202fby\u2007October\u200315, 2026."})
    void mockedEvidenceMatchesHarmlessWhitespace(String source) throws Exception {
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(RESULT);
        data.putArray("evidence").add("Please respond by October 15, 2026.");
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andRespond(withSuccess(response(data.toString()),MediaType.APPLICATION_JSON));
        var filtered=com.lifeadmin.service.DocumentEvidenceFilter.filter(provider.analyze(source),source);
        assertThat(filtered.evidence()).containsExactly("Please respond by October 15, 2026.");
        server.verify();
    }

    @Test void mockedMixedEvidenceKeepsLiteralGroundingAndEquivalentPunctuation() throws Exception {
        String source="The issuer’s “follow‑up” notice. Respond by October 15, 2026.";
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(RESULT);
        data.putArray("evidence").add("The issuer's \"follow-up\" notice.")
            .add("Reply before October 15, 2026.") // Similar meaning is insufficient.
            .add("Respond by October 16, 2026.") // Changed number.
            .add("respond by October 15, 2026.") // Changed case.
            .add("Respond by October 15, 2026.");
        var dates=data.putArray("importantDates");
        dates.addObject().put("label","Response date").put("date","2026-10-15").put("evidence","Respond by October 15, 2026.");
        dates.addObject().put("label","Unsupported date").put("date","2026-10-16").put("evidence","Respond by October 16, 2026.");
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andRespond(withSuccess(response(data.toString()),MediaType.APPLICATION_JSON));
        var filtered=com.lifeadmin.service.DocumentEvidenceFilter.filter(provider.analyze(source),source);
        assertThat(filtered.evidence()).containsExactly("The issuer's \"follow-up\" notice.","Respond by October 15, 2026.");
        assertThat(filtered.importantDates()).hasSize(1);
        assertThat(filtered.importantDates().getFirst().date()).isEqualTo(java.time.LocalDate.of(2026,10,15));
        assertThat(filtered.summary()).isEqualTo("A letter for review.");
        server.verify();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter='|',value={
        "Criminal history record check payment receipt. Fee received for the record search request.|Criminal History Record Check Receipt",
        "Electricity usage and charges for the billing period. Balance due.|Utility Bill",
        "Invoice for consulting services rendered. Payment requested.|Invoice",
        "Agency correspondence explaining how to update contact information.|Government Letter",
        "Fragment: reference 123. No further readable context.|Unknown"})
    void sendsGroundedClassificationGuidanceAndPreservesMockedTypes(String source,String type) throws Exception {
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(RESULT);
        data.put("documentType",type);
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andExpect(jsonPath("$.input[1].content").value(source))
            .andExpect(jsonPath("$.input[0].content").value(org.hamcrest.Matchers.containsString("most specific type supported")))
            .andExpect(jsonPath("$.input[0].content").value(org.hamcrest.Matchers.containsString("Use \"Unknown\" only when")))
            .andExpect(jsonPath("$.input[0].content").value(org.hamcrest.Matchers.containsString("Distinguish a request or payment receipt from the actual result")))
            .andExpect(jsonPath("$.input[0].content").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Pennsylvania"))))
            .andExpect(jsonPath("$.text.format.schema.properties.documentType.description").value(org.hamcrest.Matchers.containsString("content genuinely cannot be classified")))
            .andExpect(jsonPath("$.text.format.schema.properties.documentType.type").value("string"))
            .andExpect(jsonPath("$.text.format.schema.properties.documentType.maxLength").value(200))
            .andExpect(jsonPath("$.text.format.strict").value(true))
            .andRespond(withSuccess(response(data.toString()),MediaType.APPLICATION_JSON));
        var result=provider.analyze(source);
        assertThat(result.documentType()).isEqualTo(type);
        assertThat(result.deadline()).isNull();
        assertThat(result.actionRequired()).isNull();
        server.verify();
    }

}
