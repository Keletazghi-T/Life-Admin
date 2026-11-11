package com.lifeadmin;
import com.lifeadmin.repository.DocumentRepository;
import com.lifeadmin.repository.DocumentAnalysisRepository;
import com.lifeadmin.ai.DocumentAnalysisProvider;
import com.lifeadmin.dto.AnalysisContent;
import com.lifeadmin.service.LocalDocumentStorage;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest
@AutoConfigureMockMvc
class AnalysisIntegrationTest extends AccountTestSupport {
    static final EmbeddedPostgres postgres;
    static final Path storage;
    static {
        try { postgres = EmbeddedPostgres.builder().start(); storage = Files.createTempDirectory("life-admin-test-"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("app.storage-directory", storage::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired DocumentRepository repository;
    @Autowired ObjectMapper json;
    @MockitoSpyBean DocumentAnalysisRepository analyses;
    @MockitoBean DocumentAnalysisProvider provider;
    @MockitoSpyBean LocalDocumentStorage fileStorage;
    private static final String HEADER = "X-Life-Admin";
    @AfterAll static void cleanup() throws Exception {
        postgres.close();
        try (var files = Files.walk(storage)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
    private String upload(MockHttpSession owner, byte[] bytes) throws Exception {
        var result = mvc.perform(multipart("/api/documents/upload")
                .file(new MockMultipartFile("file", "notice.pdf", "application/pdf", bytes))
                .session(owner).with(csrf())).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
    private AnalysisContent result(Boolean action, LocalDate deadline) {
        return new AnalysisContent("Notice", "The letter requests identification.", action, deadline,
                List.of("Identification"), List.of("Review the original letter", "Gather identification"),
                List.of("Verify details against the original document."));
    }
    @Test void extractsPersistsAndReturnsAnalysisAndReusesItOnRepeatedRequests() throws Exception {
        var owner = account();
        String id = upload(owner, PdfFixtures.pdf("Provide identification by October 15, 2026."));
        when(provider.analyze(anyString())).thenReturn(result(true, LocalDate.of(2026, 10, 15)));
        mvc.perform(get("/api/documents/" + id + "/analysis").session(owner).with(csrf()))
                .andExpect(status().isNotFound());
        String body = mvc.perform(post("/api/documents/" + id + "/analyze").session(owner).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentId").value(id))
                .andExpect(jsonPath("$.deadline").value("2026-10-15"))
                .andExpect(jsonPath("$.actionRequired").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty()).andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/documents/" + id + "/analysis").session(owner).with(csrf()))
                .andExpect(status().isOk()).andExpect(content().json(body));
        mvc.perform(post("/api/documents/" + id + "/analyze").session(owner).with(csrf()))
                .andExpect(status().isOk()).andExpect(content().json(body));
        verify(provider, times(1)).analyze(contains("Provide identification by October 15, 2026."));
        var saved = analyses.findByDocumentId(UUID.fromString(id)).orElseThrow();
        assertThat(saved.getNextSteps()).containsExactly("Review the original letter", "Gather identification");
        assertThat(saved.getRequiredDocuments()).containsExactly("Identification");
        assertThat(saved.getWarnings()).containsExactly("Verify details against the original document.");
        assertThat(repository.findById(UUID.fromString(id)).orElseThrow().getStatus()).isEqualTo("UPLOADED");
    }
    @Test void preservesUnclearActionAndMissingDeadline() throws Exception {
        var owner = account();
        String id = upload(owner, PdfFixtures.pdf("An ambiguous letter without a deadline."));
        when(provider.analyze(anyString())).thenReturn(result(null, null));
        mvc.perform(post("/api/documents/" + id + "/analyze").session(owner).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actionRequired").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.deadline").value(org.hamcrest.Matchers.nullValue()));
        var saved = analyses.findByDocumentId(UUID.fromString(id)).orElseThrow();
        assertThat(saved.getActionRequired()).isNull();
        assertThat(saved.getDeadline()).isNull();
    }
    @Test void rejectsOtherSessionsAndMissingDocumentsBeforeStorageOrProviderAccess() throws Exception {
        String id = upload(account(), PdfFixtures.pdf("Private document"));
        for (String inaccessible : List.of(id, UUID.randomUUID().toString())) {
            var stranger = account();
            mvc.perform(post("/api/documents/" + inaccessible + "/analyze").session(stranger).with(csrf()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Document not found."));
            mvc.perform(get("/api/documents/" + inaccessible + "/analysis").session(stranger).with(csrf()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Document not found."));
        }
        verify(fileStorage, never()).read(anyString());
        verifyNoInteractions(provider);
    }
    @Test void savedAnalysisIsAlsoIsolated() throws Exception {
        var owner = account();
        String id = upload(owner, PdfFixtures.pdf("Private document"));
        when(provider.analyze(anyString())).thenReturn(result(false, null));
        mvc.perform(post("/api/documents/" + id + "/analyze").session(owner).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/documents/" + id + "/analysis").session(account()).with(csrf()))
                .andExpect(status().isNotFound());
    }
    @Test void invalidPdfOrMissingFileCannotCreateAnalysis() throws Exception {
        var owner = account();
        String corrupt = upload(owner, PdfFixtures.pdf("Initially valid"));
        Files.write(storage.resolve(repository.findById(UUID.fromString(corrupt)).orElseThrow().getStoredFileName()), "%PDF-1.7 broken".getBytes());
        mvc.perform(post("/api/documents/" + corrupt + "/analyze").session(owner).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        String missing = upload(owner, PdfFixtures.pdf("Test letter"));
        var doc = repository.findById(UUID.fromString(missing)).orElseThrow();
        Files.delete(storage.resolve(doc.getStoredFileName()));
        mvc.perform(post("/api/documents/" + missing + "/analyze").session(owner).with(csrf()))
                .andExpect(status().isGone());
        assertThat(analyses.findByDocumentId(UUID.fromString(corrupt))).isEmpty();
        assertThat(analyses.findByDocumentId(UUID.fromString(missing))).isEmpty();
        verifyNoInteractions(provider);
    }
    @Test void providerErrorsAndInvalidResultsAreNotSavedAndCanBeRetried() throws Exception {
        var owner = account();
        String id = upload(owner, PdfFixtures.pdf("Test letter"));
        when(provider.analyze(anyString())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider unavailable"))
                .thenReturn(new AnalysisContent("Notice", "", null, null, null, List.of(), List.of()))
                .thenReturn(result(false, null));
        String retry="";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/documents/" + id + "/analyze").session(owner).with(csrf()).contentType("application/json").content(retry))
                    .andExpect(status().isBadGateway());
            assertThat(analyses.findByDocumentId(UUID.fromString(id))).isEmpty();
            jdbc.update("update document_analysis_attempts set started_at=now()-interval '31 seconds' where document_id=?",UUID.fromString(id));
            UUID attempt=jdbc.queryForObject("select attempt_id from document_analysis_attempts where document_id=?",UUID.class,UUID.fromString(id));
            retry=json.writeValueAsString(java.util.Map.of("expectedAttemptId",attempt,"retryConfirmed",true));
        }
        mvc.perform(post("/api/documents/" + id + "/analyze").session(owner).with(csrf()).contentType("application/json").content(retry))
                .andExpect(status().isOk());
    }
    @Test void rejectsInvalidIdsAndMissingWorkspaceHeader() throws Exception {
        mvc.perform(post("/api/documents/not-a-uuid/analyze").session(account()).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents/not-a-uuid/analysis").session(account()).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/documents/" + UUID.randomUUID() + "/analyze")).andExpect(status().isForbidden());
        verifyNoInteractions(provider);
    }    @Test void suggestionsRequireExplicitConfirmationAndSelectedActionsAreIdempotent() throws Exception {
        var owner=account();String id=upload(owner,PdfFixtures.pdf("Provide identification by October 15, 2026."));
        when(provider.analyze(anyString())).thenReturn(result(true,LocalDate.of(2026,10,15)));
        var response=mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf())).andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/documents/"+id+"/tasks/generate").session(owner).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get("/api/documents/"+id+"/tasks").session(owner)).andExpect(content().json("[]"));
        var original=json.readTree(response.getResponse().getContentAsString());
        var edited=new AnalysisContent("Reviewed notice","My corrected explanation",true,LocalDate.of(2026,10,16),List.of("Passport"),List.of("Call issuing organization","Prepare reply"),List.of("Verify dates"),"Confirmed meaning",List.of(),List.of(new AnalysisContent.ImportantDate("Appointment",LocalDate.of(2026,10,14),"User reviewed date")));
        String confirmation=json.writeValueAsString(java.util.Map.of("content",edited,"expectedVersion",original.get("updatedAt").asText()));
        var saved=json.readTree(mvc.perform(put("/api/documents/"+id+"/analysis/confirm").session(owner).with(csrf()).contentType("application/json").content(confirmation)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(saved.path("summary").asText()).isEqualTo("The letter requests identification.");assertThat(saved.path("confirmedContent").path("summary").asText()).isEqualTo("My corrected explanation");
        String selected=json.writeValueAsString(java.util.Map.of("steps",List.of(0,0),"dates",List.of(0),"includeDeadline",true,"confirmedAt",saved.path("confirmedAt").asText()));
        for(int i=0;i<2;i++)mvc.perform(post("/api/documents/"+id+"/tasks/generate").session(owner).with(csrf()).contentType("application/json").content(selected)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        var tasks=json.readTree(mvc.perform(get("/api/documents/"+id+"/tasks").session(owner)).andReturn().getResponse().getContentAsString());
        assertThat(tasks.toString()).contains("Call issuing organization","Appointment","2026-10-16").doesNotContain("Prepare reply","Gather identification");
        mvc.perform(delete("/api/tasks/"+tasks.get(0).path("id").asText()).session(owner).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/api/documents/"+id+"/tasks/generate").session(owner).with(csrf()).contentType("application/json").content(selected)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(put("/api/documents/"+id+"/analysis/confirm").session(owner).with(csrf()).contentType("application/json").content(confirmation)).andExpect(status().isConflict());
        mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf())).andExpect(jsonPath("$.confirmedContent.summary").value("My corrected explanation"));
        verify(provider,times(1)).analyze(anyString());
    }
    @Test void manualConfirmationWorksWithoutAiAndIsAccountScopedAndCsrfProtected() throws Exception {
        var owner=account();var other=account();String id=upload(owner,PdfFixtures.pdf("Manual review fixture"));
        String input=json.writeValueAsString(java.util.Map.of("content",result(null,null)));
        mvc.perform(put("/api/documents/"+id+"/analysis/confirm").session(owner).contentType("application/json").content(input)).andExpect(status().isForbidden());
        mvc.perform(put("/api/documents/"+id+"/analysis/confirm").session(other).with(csrf()).contentType("application/json").content(input)).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/"+id+"/analysis-attempt").session(other)).andExpect(status().isNotFound());
        mvc.perform(put("/api/documents/"+id+"/analysis/confirm").session(owner).with(csrf()).contentType("application/json").content(input)).andExpect(status().isOk()).andExpect(jsonPath("$.source").value("MANUAL")).andExpect(jsonPath("$.confirmedAt").isNotEmpty());
        mvc.perform(post("/api/documents/"+id+"/tasks/generate").session(other).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf())).andExpect(status().isOk());verifyNoInteractions(provider);
    }
    @Test void failedAttemptPersistsAndRequiresConfirmationCurrentVersionAndCooldown() throws Exception {
        var owner=account();String id=upload(owner,PdfFixtures.pdf("Original document must be preserved"));
        var doc=repository.findById(UUID.fromString(id)).orElseThrow();var bytes=Files.readAllBytes(storage.resolve(doc.getStoredFileName()));
        when(provider.analyze(anyString())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Fixture failure"));
        mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf())).andExpect(status().isBadGateway());
        var attempt=json.readTree(mvc.perform(get("/api/documents/"+id+"/analysis-attempt").session(owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(attempt.path("status").asText()).isEqualTo("FAILED");
        mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf())).andExpect(status().isConflict());
        String retry=json.writeValueAsString(java.util.Map.of("retryConfirmed",true,"expectedAttemptId",attempt.path("attemptId").asText()));
        mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf()).contentType("application/json").content(retry)).andExpect(status().isTooManyRequests());
        verify(provider,times(1)).analyze(anyString());
        jdbc.update("update document_analysis_attempts set started_at=now()-interval '31 seconds' where document_id=?",UUID.fromString(id));
        doReturn(result(false,null)).when(provider).analyze(anyString());
        mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf()).contentType("application/json").content(retry)).andExpect(status().isOk());
        verify(provider,times(2)).analyze(anyString());assertThat(Files.readAllBytes(storage.resolve(doc.getStoredFileName()))).isEqualTo(bytes);
    }
    @Test void concurrentAnalysisClaimsOnlyOneProviderAttemptAndManualConfirmationWins() throws Exception {
        var owner=account();String id=upload(owner,PdfFixtures.pdf("Document with sufficient readable text"));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(provider.analyze(anyString())).thenAnswer(invocation->{entered.countDown();assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return result(true,null);});
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf())).andReturn());
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf())).andExpect(status().isConflict());
                mvc.perform(put("/api/documents/"+id+"/analysis/confirm").session(owner).with(csrf()).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("content",result(false,null))))).andExpect(status().isOk());
            } finally {release.countDown();}
            assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        mvc.perform(get("/api/documents/"+id+"/analysis").session(owner)).andExpect(jsonPath("$.source").value("MANUAL")).andExpect(jsonPath("$.confirmedContent.actionRequired").value(false));verify(provider,times(1)).analyze(anyString());
    }
    @Test void insufficientTextAndImageOnlyPdfNeverReachProvider() throws Exception {
        var owner=account();
        for(String text:List.of("","abc")) {
            String id=upload(owner,PdfFixtures.pdf(text));
            mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf())).andExpect(status().isUnprocessableEntity());
            assertThat(repository.findById(UUID.fromString(id))).isPresent();
            mvc.perform(get("/api/documents/"+id+"/analysis-attempt").session(owner)).andExpect(jsonPath("$.status").value("FAILED"));
        }
        verifyNoInteractions(provider);
    }

    @Test void migrationPreservesHistoricalSuggestionsTasksAndReceiptRows() throws Exception {
        String schema="m20_legacy_fixture";String url=postgres.getJdbcUrl("postgres","postgres");
        org.flywaydb.core.Flyway.configure().dataSource(url,"postgres","postgres").schemas(schema).target("13").load().migrate();
        UUID user=UUID.randomUUID(),document=UUID.randomUUID(),analysis=UUID.randomUUID(),receipt=UUID.randomUUID(),task=UUID.randomUUID();
        jdbc.update("insert into "+schema+".users(id,email,password_hash,created_at,updated_at) values(?, 'm20-legacy@example.test','fixture',now(),now())",user);
        jdbc.update("insert into "+schema+".documents(id,user_id,original_file_name,display_name,stored_file_name,file_type,file_size,upload_date,status) values(?,?,'original.pdf','Original','original-key.pdf','application/pdf',10,now(),'UPLOADED')",document,user);
        jdbc.update("insert into "+schema+".document_analyses(id,document_id,document_type,summary,action_required,deadline,required_documents,next_steps,warnings,created_at,updated_at,source) values(?,?,'Notice','Existing explanation',true,'2026-10-15','[]','[\"Existing action\"]','[]',now(),now(),'AI')",analysis,document);
        jdbc.update("insert into "+schema+".tasks(id,document_id,title,status,created_at,updated_at) values(?,?,'Existing task','TODO',now(),now())",task,document);
        jdbc.update("insert into "+schema+".receipts(id,user_id,merchant,purchase_date,amount,currency,original_file_name,stored_file_name,content_type,file_size,status,created_at,updated_at) values(?,?,'Existing merchant','2025-01-01',12.34,'USD','receipt.pdf','receipt-key.pdf','application/pdf',10,'SAVED',now(),now())",receipt,user);
        String before=jdbc.queryForObject("select to_jsonb(a)::text from "+schema+".document_analyses a where id=?",String.class,analysis);
        var receiptBefore=jdbc.queryForList("select * from "+schema+".receipts");var taskBefore=jdbc.queryForList("select * from "+schema+".tasks");
        org.flywaydb.core.Flyway.configure().dataSource(url,"postgres","postgres").schemas(schema).load().migrate();
        assertThat(jdbc.queryForObject("select (to_jsonb(a)-'meaning'-'evidence'-'important_dates'-'confirmed_content'-'confirmed_at')::text from "+schema+".document_analyses a where id=?",String.class,analysis)).isEqualTo(before);
        assertThat(jdbc.queryForList("select * from "+schema+".receipts")).isEqualTo(receiptBefore);assertThat(jdbc.queryForList("select * from "+schema+".tasks")).isEqualTo(taskBefore);
        assertThat(jdbc.queryForObject("select confirmed_at is null and confirmed_content is null from "+schema+".document_analyses where id=?",Boolean.class,analysis)).isTrue();
    }
    @Test void confirmationValidationAndSelectionVersionCannotBeBypassed() throws Exception {
        var owner=account();String id=upload(owner,PdfFixtures.pdf("Manual validation document"));
        var invalid=new AnalysisContent("", "",null,null,List.of(),List.of(),List.of());
        mvc.perform(put("/api/documents/"+id+"/analysis/confirm").session(owner).with(csrf()).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("content",invalid)))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/documents/"+id+"/analysis/confirm").session(owner).with(csrf()).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("content",result(false,null))))).andExpect(status().isOk());
        mvc.perform(post("/api/documents/"+id+"/tasks/generate").session(owner).with(csrf()).contentType("application/json").content("{\"steps\":[0],\"dates\":[],\"confirmedAt\":\"2020-01-01T00:00:00Z\"}")).andExpect(status().isConflict());
        var current=analyses.findByDocumentId(UUID.fromString(id)).orElseThrow();
        mvc.perform(post("/api/documents/"+id+"/tasks/generate").session(owner).with(csrf()).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("steps",List.of(999),"dates",List.of(),"confirmedAt",current.getConfirmedAt())))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents/"+id+"/tasks").session(owner)).andExpect(content().json("[]"));verifyNoInteractions(provider);
    }

    private com.fasterxml.jackson.databind.JsonNode diagnostic(String id) throws Exception {
        return json.readTree(jdbc.queryForObject("select diagnostics::text from document_analysis_attempts where document_id=?",String.class,UUID.fromString(id)));
    }
    @Test void safeDiagnosticsPersistExtractionValidationAndSuccessWithoutContent() throws Exception {
        var owner=account();String id=upload(owner,PdfFixtures.pdf("PRIVATE document contents for diagnostic fixture"));
        when(provider.analyze(anyString())).thenReturn(result(true,null));
        mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf())).andExpect(status().isOk());
        var safe=diagnostic(id);assertThat(safe.path("stage").asText()).isEqualTo("PERSISTENCE");assertThat(safe.path("outcome").asText()).isEqualTo("SUCCEEDED");assertThat(safe.path("extractionCompleted").asBoolean()).isTrue();
        // This test uses a provider mock, so it correctly cannot assert HTTP dispatch occurred.
        assertThat(safe.path("httpRequestStarted").asBoolean()).isFalse();
        assertThat(safe.path("attemptId").asText()).isEqualTo(jdbc.queryForObject("select attempt_id::text from document_analysis_attempts where document_id=?",String.class,UUID.fromString(id)));
        assertThat(safe.toString()).doesNotContain("PRIVATE","identification","Authorization","summary");
        mvc.perform(get("/api/documents/"+id+"/analysis-attempt").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.diagnostics").doesNotExist());
    }
    @Test void extractionFailureAndInvalidProviderResultHaveSafeDistinctPersistedStages() throws Exception {
        var owner=account();String empty=upload(owner,PdfFixtures.pdf(""));
        mvc.perform(post("/api/documents/"+empty+"/analyze").session(owner).with(csrf())).andExpect(status().isUnprocessableEntity());
        assertThat(diagnostic(empty).path("stage").asText()).isEqualTo("PRE_REQUEST");assertThat(diagnostic(empty).path("extractionCompleted").asBoolean()).isFalse();
        String invalid=upload(owner,PdfFixtures.pdf("Readable text for validation fixture"));
        when(provider.analyze(anyString())).thenReturn(new AnalysisContent("", "",null,null,List.of(),List.of(),List.of()));
        mvc.perform(post("/api/documents/"+invalid+"/analyze").session(owner).with(csrf())).andExpect(status().isBadGateway());
        assertThat(diagnostic(invalid).path("stage").asText()).isEqualTo("RESPONSE_VALIDATION");assertThat(diagnostic(invalid).path("outcome").asText()).isEqualTo("FAILED");assertThat(diagnostic(invalid).path("extractionCompleted").asBoolean()).isTrue();
    }
    @Test void persistenceFailureRetainsSafeDiagnosticAndOriginalFile() throws Exception {
        var owner=account();String id=upload(owner,PdfFixtures.pdf("Original document remains stored"));
        var document=repository.findById(UUID.fromString(id)).orElseThrow();byte[] before=Files.readAllBytes(storage.resolve(document.getStoredFileName()));
        when(provider.analyze(anyString())).thenReturn(result(false,null));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE SQL AND DOCUMENT CONTENTS")).when(analyses).saveAndFlush(any(com.lifeadmin.entity.DocumentAnalysis.class));
        mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf())).andExpect(status().isBadGateway()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE"))));
        var safe=diagnostic(id);assertThat(safe.path("stage").asText()).isEqualTo("PERSISTENCE");assertThat(safe.path("outcome").asText()).isEqualTo("FAILED");assertThat(safe.path("exceptionClass").asText()).endsWith("DataAccessResourceFailureException");assertThat(safe.toString()).doesNotContain("PRIVATE","CONTENTS");
        assertThat(Files.readAllBytes(storage.resolve(document.getStoredFileName()))).isEqualTo(before);assertThat(analyses.findByDocumentId(UUID.fromString(id))).isEmpty();
    }

    @Test void serviceValidationReasonsArePersistedWithoutRejectedValues() throws Exception {
        record Case(AnalysisContent content,String reason,String field) {}
        var cases=List.of(
            new Case(new AnalysisContent("Notice","   ",null,null,List.of(),List.of(),List.of()),"REQUIRED_VALUE_BLANK","summary"),
            new Case(new AnalysisContent("Notice","PRIVATE".repeat(1200),null,null,List.of(),List.of(),List.of()),"FIELD_LENGTH_EXCEEDED","summary"),
            new Case(new AnalysisContent("Notice","Summary",null,null,List.of(),java.util.Collections.nCopies(51,"PRIVATE STEP"),List.of()),"LIST_LIMIT_EXCEEDED","nextSteps"),
            new Case(new AnalysisContent("Notice","Summary",null,LocalDate.of(0,1,1),List.of(),List.of(),List.of()),"DATE_YEAR_OUT_OF_RANGE","deadline"));
        var owner=account();
        for(var item:cases) {
            String id=upload(owner,PdfFixtures.pdf("PRIVATE diagnostic test document"));
            when(provider.analyze(anyString())).thenReturn(item.content());
            mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf())).andExpect(status().isBadGateway());
            var safe=diagnostic(id);assertThat(safe.path("validationReason").asText()).isEqualTo(item.reason());assertThat(safe.path("validationField").asText()).isEqualTo(item.field());
            assertThat(safe.toString()).doesNotContain("PRIVATE","0000-01-01");assertThat(analyses.findByDocumentId(UUID.fromString(id))).isEmpty();
        }
    }

    @Test void persistsOnlyVerifiedEvidenceWithoutLosingAnalysisOrRetrying() throws Exception {
        var owner=account();
        String id=upload(owner,PdfFixtures.pdf("Respond by\nOctober   15, 2026."));
        var document=repository.findById(UUID.fromString(id)).orElseThrow();
        byte[] original=Files.readAllBytes(storage.resolve(document.getStoredFileName()));
        when(provider.analyze(anyString())).thenReturn(new AnalysisContent("Notice","Review this notice.",true,null,
            List.of(),List.of("Review the original"),List.of(),null,
            List.of("Respond by October 15, 2026.","Unsupported excerpt"),
            List.of(new AnalysisContent.ImportantDate("Response date",LocalDate.of(2026,10,15),"Respond by October 15, 2026."),
                new AnalysisContent.ImportantDate("Unsupported date",LocalDate.of(2026,10,16),"Unsupported excerpt"))));
        for(int i=0;i<2;i++) mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.evidence.length()").value(1))
            .andExpect(jsonPath("$.importantDates.length()").value(1))
            .andExpect(jsonPath("$.summary").value("Review this notice."));
        mvc.perform(get("/api/documents/"+id+"/analysis").session(owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.importantDates[0].date").value("2026-10-15"));
        verify(provider,times(1)).analyze(anyString());
        assertThat(Files.readAllBytes(storage.resolve(document.getStoredFileName()))).isEqualTo(original);
        assertThat(diagnostic(id).path("outcome").asText()).isEqualTo("SUCCEEDED");
    }

    @Test void evidenceFilteringCannotHideInvalidFieldsLimitsOrDateYears() throws Exception {
        var owner=account();
        var invalid=List.of(
            new AnalysisContent("Notice","Summary",null,null,List.of(),List.of(),List.of(),null,List.of(" "),List.of()),
            new AnalysisContent("Notice","Summary",null,null,List.of(),List.of(),List.of(),null,List.of("x".repeat(1001)),List.of()),
            new AnalysisContent("Notice","Summary",null,null,List.of(),List.of(),List.of(),null,java.util.Collections.nCopies(21,"Unsupported"),List.of()),
            new AnalysisContent("Notice","Summary",null,null,List.of(),List.of(),List.of(),null,List.of(),List.of(new AnalysisContent.ImportantDate(" ",LocalDate.of(2026,10,15),"Unsupported"))),
            new AnalysisContent("Notice","Summary",null,null,List.of(),List.of(),List.of(),null,List.of(),List.of(new AnalysisContent.ImportantDate("Date",LocalDate.of(0,1,1),"Unsupported"))));
        for(var content:invalid) {
            String id=upload(owner,PdfFixtures.pdf("Unrelated source document"));
            when(provider.analyze(anyString())).thenReturn(content);
            mvc.perform(post("/api/documents/"+id+"/analyze").session(owner).with(csrf())).andExpect(status().isBadGateway());
            assertThat(analyses.findByDocumentId(UUID.fromString(id))).isEmpty();
        }
    }

}
