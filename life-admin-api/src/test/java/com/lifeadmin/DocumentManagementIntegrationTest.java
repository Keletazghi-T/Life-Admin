package com.lifeadmin;
import com.lifeadmin.repository.*;
import com.lifeadmin.service.LocalDocumentStorage;
import com.lifeadmin.ai.DocumentAnalysisProvider;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.profiles.active=dev","app.development.mock-analysis-enabled=true","app.ai.openai.api-key="})
@AutoConfigureMockMvc
class DocumentManagementIntegrationTest extends AccountTestSupport {
    static final EmbeddedPostgres postgres;
    static final Path storage;
    static { try { postgres=EmbeddedPostgres.builder().start();storage=Files.createTempDirectory("life-admin-management-test-"); } catch(Exception e) {throw new ExceptionInInitializerError(e);} }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");r.add("app.storage-directory",storage::toString);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired DocumentRepository documents;
    @Autowired DocumentAnalysisRepository analyses; @Autowired TaskRepository tasks; @Autowired ReminderRepository reminders;
    @Autowired TaskGenerationRepository generations; @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean LocalDocumentStorage files; @MockitoBean DocumentAnalysisProvider provider;
    @AfterEach void noAi() {verifyNoInteractions(provider);}
    @AfterAll static void cleanup() throws Exception {postgres.close();try(var all=Files.walk(storage)){for(var file:all.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    private String upload(MockHttpSession owner,String name) throws Exception {return upload(owner,name,PdfFixtures.pdf("Private document"));}
    private String upload(MockHttpSession owner,String name,byte[] bytes) throws Exception {
        var result=mvc.perform(multipart("/api/documents/upload").file(new MockMultipartFile("file",name,"application/pdf",bytes)).session(owner).with(csrf()))
            .andExpect(status().isCreated()).andReturn();return body(result).get("id").asText();
    }
    private JsonNode body(MvcResult result) throws Exception {return json.readTree(result.getResponse().getContentAsString());}
    private ResultActions rename(MockHttpSession owner,String id,String name) throws Exception {
        return mvc.perform(patch("/api/documents/"+id).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("displayName",name))));
    }
    @Test void originalBytesAndSafeHeadersAreServedOnlyToOwner() throws Exception {
        var owner=account();byte[] bytes=PdfFixtures.pdf("Exact original bytes");String id=upload(owner,"../../Private.pdf",bytes);
        var stored=documents.findById(UUID.fromString(id)).orElseThrow().getStoredFileName();
        for(boolean download:List.of(false,true)) {
            var result=mvc.perform(get("/api/documents/"+id+"/file").param("download",String.valueOf(download)).session(owner))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andExpect(content().bytes(bytes))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(header().string("Cache-Control","private, no-store")).andReturn();
            var disposition=ContentDisposition.parse(result.getResponse().getHeader("Content-Disposition"));
            assertThat(disposition.getType()).isEqualTo(download ? "attachment" : "inline");assertThat(disposition.getFilename()).isEqualTo("Private.pdf");
            assertThat(result.getResponse().getHeader("Content-Disposition")).doesNotContain(stored,storage.toString());
        }
        rename(owner,id,"../../Résumé \"quoted\"; notice").andExpect(status().isOk());
        var response=mvc.perform(get("/api/documents/"+id+"/file?download=true").session(owner)).andExpect(status().isOk()).andReturn().getResponse();
        String filename=ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename();
        assertThat(filename).endsWith(".pdf").doesNotContain("/","\\","\"","\r","\n");assertThat(response.getHeader("X-Injected")).isNull();
    }
    @Test void crossUserFileDownloadRenameDeleteAndUnauthenticatedAccessFailBeforeStorage() throws Exception {
        var owner=account();var other=account();String id=upload(owner,"Private.pdf");clearInvocations(files);
        for(String candidate:List.of(id,UUID.randomUUID().toString())) {
            for(String suffix:List.of("/file","/file?download=true")) mvc.perform(get("/api/documents/"+candidate+suffix).session(other)).andExpect(status().isNotFound());
            rename(other,candidate,"Stolen").andExpect(status().isNotFound());
            mvc.perform(delete("/api/documents/"+candidate).session(other).with(csrf())).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/documents/"+id+"/file")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/documents/not-an-id/file").session(owner)).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/documents/"+id).session(owner)).andExpect(status().isForbidden());
        verify(files,never()).read(anyString());verify(files,never()).delete(anyString());
        assertThat(documents.findById(UUID.fromString(id))).isPresent();
    }
    @Test void renamePersistsWithoutChangingOriginalNameOrPhysicalFileAndRejectsInvalidNames() throws Exception {
        var owner=account();String id=upload(owner,"Original.pdf");var before=documents.findById(UUID.fromString(id)).orElseThrow();
        rename(owner,id,"  Housing Assistance Notice  ").andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Housing Assistance Notice"))
            .andExpect(jsonPath("$.originalFileName").value("Original.pdf")).andExpect(jsonPath("$.storedFileName").doesNotExist());
        mvc.perform(get("/api/documents/"+id).session(owner)).andExpect(jsonPath("$.displayName").value("Housing Assistance Notice"));
        var after=documents.findById(UUID.fromString(id)).orElseThrow();assertThat(after.getStoredFileName()).isEqualTo(before.getStoredFileName());assertThat(Files.exists(storage.resolve(after.getStoredFileName()))).isTrue();
        for(String name:List.of("","  ","x".repeat(256),"Name\r\nX-Injected: yes","Name\u202Efdp")) rename(owner,id,name).andExpect(status().isBadRequest());
    }
    @Test void searchIsLiteralCaseInsensitiveOwnedAndSupportsAllSortOrders() throws Exception {
        var owner=account();String zebra=upload(owner,"Zebra.pdf"),alpha=upload(owner,"alpha.pdf"),percent=upload(owner,"100%.pdf");
        upload(account(),"alpha-other.pdf");
        jdbc.update("update documents set upload_date=? where id=?",java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),UUID.fromString(zebra));
        jdbc.update("update documents set upload_date=? where id=?",java.sql.Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")),UUID.fromString(alpha));
        jdbc.update("update documents set upload_date=? where id=?",java.sql.Timestamp.from(Instant.parse("2026-01-03T00:00:00Z")),UUID.fromString(percent));
        for(String[] pair:List.of(new String[]{"newest",percent},new String[]{"oldest",zebra},new String[]{"name-asc",percent},new String[]{"name-desc",zebra})) {
            mvc.perform(get("/api/documents").param("sort",pair[0]).session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$[0].id").value(pair[1]));
        }
        mvc.perform(get("/api/documents").param("search","ALPHA").session(owner)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(alpha));
        mvc.perform(get("/api/documents").param("search","%").session(owner)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(percent));
        rename(owner,alpha,"Benefits letter").andExpect(status().isOk());
        mvc.perform(get("/api/documents").param("search","alpha").session(owner)).andExpect(content().json("[]"));
        mvc.perform(get("/api/documents").param("sort","invalid").session(owner)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents").param("search","x".repeat(256)).session(owner)).andExpect(status().isBadRequest());
    }
    private String populate(MockHttpSession owner,String doc) throws Exception {
        mvc.perform(post("/api/development/documents/"+doc+"/mock-analysis").session(owner).with(csrf())).andExpect(status().isOk());
        confirmFixtureAnalysis(doc);
        String task=body(mvc.perform(post("/api/documents/"+doc+"/tasks/generate").session(owner).with(csrf())).andExpect(status().isOk()).andReturn()).get(0).get("id").asText();
        mvc.perform(put("/api/tasks/"+task).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Respond\",\"dueDate\":\""+LocalDate.now(ZoneOffset.UTC).plusDays(30)+"\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/"+task+"/reminders").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"daysBefore\":7}")).andExpect(status().isCreated());
        return task;
    }
    @Test void deletionCascadesAllRelatedRecordsAndRemovesFileButKeepsUser() throws Exception {
        var owner=account();String id=upload(owner,"TestRecord.pdf");String task=populate(owner,id);
        var doc=documents.findById(UUID.fromString(id)).orElseThrow();var analysis=analyses.findByDocumentId(doc.getId()).orElseThrow();
        var reminder=reminders.findByTaskIdOrderByReminderDateAscIdAsc(UUID.fromString(task)).getFirst();
        assertThat(generations.existsById(analysis.getId())).isTrue();
        mvc.perform(delete("/api/documents/"+id).session(owner).with(csrf())).andExpect(status().isNoContent());
        assertThat(documents.existsById(doc.getId())).isFalse();assertThat(analyses.existsById(analysis.getId())).isFalse();
        assertThat(tasks.findByDocumentIdAndDocumentUserIdOrderByCreatedAtAscIdAsc(doc.getId(),doc.getUserId())).isEmpty();
        assertThat(reminders.existsById(reminder.getId())).isFalse();assertThat(generations.existsById(analysis.getId())).isFalse();
        assertThat(Files.exists(storage.resolve(doc.getStoredFileName()))).isFalse();assertThat(users.existsById(doc.getUserId())).isTrue();
        mvc.perform(get("/api/documents/"+id+"/file").session(owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/upcoming").session(owner)).andExpect(content().json("[]"));
    }
    @Test void fileCleanupFailureIsExplicitDurablePrivateAndRetryable() throws Exception {
        var owner=account();String id=upload(owner,"TestRecord.pdf");String task=populate(owner,id);
        var doc=documents.findById(UUID.fromString(id)).orElseThrow();
        doThrow(new IllegalStateException("sensitive/path/must/not/leak")).when(files).delete(doc.getStoredFileName());
        var response=mvc.perform(delete("/api/documents/"+id).session(owner).with(csrf())).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("sensitive",doc.getStoredFileName());
        mvc.perform(get("/api/documents/"+id).session(owner)).andExpect(jsonPath("$.status").value("DELETION_PENDING"));
        assertThat(Files.exists(storage.resolve(doc.getStoredFileName()))).isTrue();assertThat(tasks.existsById(UUID.fromString(task))).isTrue();
        mvc.perform(get("/api/documents/"+id+"/file").session(owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/"+task+"/reminders").session(owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/upcoming").session(owner)).andExpect(content().json("[]"));
        rename(owner,id,"No longer editable").andExpect(status().isNotFound());
        doCallRealMethod().when(files).delete(doc.getStoredFileName());
        mvc.perform(delete("/api/documents/"+id).session(owner).with(csrf())).andExpect(status().isNoContent());
        assertThat(documents.existsById(doc.getId())).isFalse();assertThat(Files.exists(storage.resolve(doc.getStoredFileName()))).isFalse();
    }
    @Test void databaseFailureAfterFileRemovalLeavesDurableMarkerAndRetryFinishes() throws Exception {
        var owner=account();String id=upload(owner,"TestRecord.pdf");var doc=documents.findById(UUID.fromString(id)).orElseThrow();
        jdbc.execute("CREATE FUNCTION reject_document_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'simulated database failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_document_delete BEFORE DELETE ON documents FOR EACH ROW EXECUTE FUNCTION reject_document_delete()");
        try {
            mvc.perform(delete("/api/documents/"+id).session(owner).with(csrf())).andExpect(status().isServiceUnavailable());
            assertThat(documents.findById(doc.getId()).orElseThrow().getStatus()).isEqualTo("DELETION_PENDING");assertThat(Files.exists(storage.resolve(doc.getStoredFileName()))).isFalse();
        } finally {jdbc.execute("DROP TRIGGER reject_document_delete ON documents");jdbc.execute("DROP FUNCTION reject_document_delete()");}
        mvc.perform(delete("/api/documents/"+id).session(owner).with(csrf())).andExpect(status().isNoContent());assertThat(documents.existsById(doc.getId())).isFalse();
    }
    @Test void malformedPdfIsRejectedAndMissingStoredPdfIsHandledGracefully() throws Exception {
        var owner=account();
        mvc.perform(multipart("/api/documents/upload").file(new MockMultipartFile("file","malformed.pdf","application/pdf","%PDF-1.7 broken".getBytes())).session(owner).with(csrf()))
            .andExpect(status().isBadRequest());
        String id=upload(owner,"Valid.pdf");var doc=documents.findById(UUID.fromString(id)).orElseThrow();Files.delete(storage.resolve(doc.getStoredFileName()));
        mvc.perform(get("/api/documents/"+id+"/file").session(owner)).andExpect(status().isGone());
        mvc.perform(delete("/api/documents/"+id).session(owner).with(csrf())).andExpect(status().isNoContent());
        assertThatThrownBy(()->files.read("../secret.pdf")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->files.delete("../secret.pdf")).isInstanceOf(IllegalArgumentException.class);
    }
}
