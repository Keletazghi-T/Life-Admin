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
class AccountManagementIntegrationTest extends AccountTestSupport {
    static final EmbeddedPostgres postgres;
    static final Path storage;
    static { try { postgres=EmbeddedPostgres.builder().start();storage=Files.createTempDirectory("life-admin-account-test-"); } catch(Exception e) {throw new ExceptionInInitializerError(e);} }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");r.add("app.storage-directory",storage::toString);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired DocumentRepository documents;
    @Autowired DocumentAnalysisRepository analyses; @Autowired TaskRepository tasks; @Autowired ReminderRepository reminders;
    @Autowired TaskGenerationRepository generations; @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @MockitoSpyBean LocalDocumentStorage files; @MockitoBean DocumentAnalysisProvider provider;
    @AfterEach void noAi() {verifyNoInteractions(provider);}
    @AfterAll static void cleanup() throws Exception {postgres.close();try(var all=Files.walk(storage)){for(var file:all.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    private static final String OLD="Fixture-only-password", NEW="Changed-password-2026";
    private UUID owner(MockHttpSession session) {
        var context=(org.springframework.security.core.context.SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT");
        return ((com.lifeadmin.security.AccountPrincipal)context.getAuthentication().getPrincipal()).id();
    }
    private ResultActions password(MockHttpSession session,String old,String next,String confirm) throws Exception {
        return mvc.perform(put("/api/account/password").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("currentPassword",old,"newPassword",next,"confirmNewPassword",confirm))));
    }
    private ResultActions remove(MockHttpSession session,String old,String confirmation) throws Exception {
        return mvc.perform(delete("/api/account").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("currentPassword",old,"confirmation",confirmation))));
    }
    private ResultActions login(String email,String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",email,"password",password))));
    }
    @Test void passwordChangeHashesRevokesEverySessionAndRequiresNewCredentials() throws Exception {
        var session=account();var id=owner(session);var before=users.findById(id).orElseThrow();
        var other=(MockHttpSession)login(before.getEmail(),OLD).andExpect(status().isOk()).andReturn().getRequest().getSession();
        password(session,OLD,NEW,NEW).andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(session.isInvalid()).isTrue();
        var updated=users.findById(id).orElseThrow();assertThat(updated.getPasswordHash()).startsWith("$2").isNotEqualTo(NEW).isNotEqualTo(before.getPasswordHash());assertThat(passwords.matches(NEW,updated.getPasswordHash())).isTrue();
        mvc.perform(get("/api/auth/me").session(other)).andExpect(status().isUnauthorized());assertThat(other.isInvalid()).isTrue();
        login(before.getEmail(),OLD).andExpect(status().isUnauthorized());
        var result=login(before.getEmail(),NEW).andExpect(status().isOk()).andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn();
        mvc.perform(get("/api/auth/me").session((MockHttpSession)result.getRequest().getSession())).andExpect(status().isOk()).andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
    @Test void invalidPasswordChangesLeaveHashAndSessionUnchanged() throws Exception {
        var session=account();var id=owner(session);String hash=users.findById(id).orElseThrow().getPasswordHash();
        for(String[] values:List.of(new String[]{"incorrect",NEW,NEW},new String[]{OLD,"short","short"},new String[]{OLD,NEW,"Mismatch-123"},new String[]{OLD,"é".repeat(37),"é".repeat(37)},new String[]{"",NEW,NEW},new String[]{OLD," ".repeat(8)," ".repeat(8)}))
            password(session,values[0],values[1],values[2]).andExpect(status().isBadRequest());
        assertThat(users.findById(id).orElseThrow().getPasswordHash()).isEqualTo(hash);assertThat(session.isInvalid()).isFalse();
        mvc.perform(put("/api/account/password").session(session).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/account/password").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }
    private UUID upload(MockHttpSession owner) throws Exception {
        var result=mvc.perform(multipart("/api/documents/upload").file(new MockMultipartFile("file","TestRecord.pdf","application/pdf",PdfFixtures.pdf("Private"))).session(owner).with(csrf())).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
    private UUID populate(MockHttpSession owner,UUID doc) throws Exception {
        mvc.perform(post("/api/development/documents/"+doc+"/mock-analysis").session(owner).with(csrf())).andExpect(status().isOk());
        confirmFixtureAnalysis(doc.toString());
        var result=mvc.perform(post("/api/documents/"+doc+"/tasks/generate").session(owner).with(csrf())).andExpect(status().isOk()).andReturn();
        UUID task=UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get(0).get("id").asText());
        mvc.perform(put("/api/tasks/"+task).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Respond\",\"dueDate\":\""+LocalDate.now(ZoneOffset.UTC).plusDays(30)+"\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/"+task+"/reminders").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"daysBefore\":7}")).andExpect(status().isCreated());return task;
    }
    @Test void deletionRemovesAllOwnedDataFilesCredentialsAndSessionsLeavingOtherUserUntouched() throws Exception {
        var a=account();var b=account();var user=users.findById(owner(a)).orElseThrow();var bId=owner(b);
        var otherSession=(MockHttpSession)login(user.getEmail(),OLD).andReturn().getRequest().getSession();
        var aDoc=upload(a);var bDoc=upload(b);var aTask=populate(a,aDoc);var bTask=populate(b,bDoc);
        var file=storage.resolve(documents.findById(aDoc).orElseThrow().getStoredFileName());
        var bFile=storage.resolve(documents.findById(bDoc).orElseThrow().getStoredFileName());byte[] bBytes=Files.readAllBytes(bFile);
        var analysis=analyses.findByDocumentId(aDoc).orElseThrow();var reminder=reminders.findByTaskIdOrderByReminderDateAscIdAsc(aTask).getFirst();
        for(var pair:List.of(Map.entry(a,bDoc),Map.entry(b,aDoc))) {
            for(String suffix:List.of("","/file","/analysis","/tasks")) mvc.perform(get("/api/documents/"+pair.getValue()+suffix).session(pair.getKey())).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/tasks/"+aTask+"/reminders").session(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/"+bTask+"/reminders").session(a)).andExpect(status().isNotFound());
        remove(a,OLD,"DELETE").andExpect(status().isNoContent());assertThat(a.isInvalid()).isTrue();
        assertThat(users.existsById(user.getId())).isFalse();assertThat(documents.existsById(aDoc)).isFalse();assertThat(analyses.existsById(analysis.getId())).isFalse();assertThat(generations.existsById(analysis.getId())).isFalse();assertThat(tasks.existsById(aTask)).isFalse();assertThat(reminders.existsById(reminder.getId())).isFalse();assertThat(Files.exists(file)).isFalse();
        login(user.getEmail(),OLD).andExpect(status().isUnauthorized());mvc.perform(get("/api/auth/me").session(otherSession)).andExpect(status().isUnauthorized());
        assertThat(users.existsById(bId)).isTrue();assertThat(documents.existsById(bDoc)).isTrue();assertThat(analyses.findByDocumentId(bDoc)).isPresent();assertThat(tasks.existsById(bTask)).isTrue();assertThat(reminders.findByTaskIdOrderByReminderDateAscIdAsc(bTask)).hasSize(1);assertThat(Files.readAllBytes(bFile)).isEqualTo(bBytes);
        mvc.perform(get("/api/documents/"+bDoc+"/file").session(b)).andExpect(status().isOk());
    }
    @Test void wrongPasswordConfirmationMissingBodyAndCsrfCannotDelete() throws Exception {
        var a=account();var id=owner(a);var doc=upload(a);clearInvocations(files);
        remove(a,"incorrect","DELETE").andExpect(status().isBadRequest());
        for(String confirmation:List.of("delete","DELETE ",""))remove(a,OLD,confirmation).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/account").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/account").session(a).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/account").session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        assertThat(users.findById(id).orElseThrow().isDeletionPending()).isFalse();assertThat(documents.existsById(doc)).isTrue();verify(files,never()).delete(anyString());
    }
    @Test void filesystemFailureRetainsTrackedDataBlocksUploadAndAllowsRetryAfterFreshLogin() throws Exception {
        var a=account();var id=owner(a);var email=users.findById(id).orElseThrow().getEmail();var doc=upload(a);var task=populate(a,doc);var name=documents.findById(doc).orElseThrow().getStoredFileName();
        doThrow(new IllegalStateException("private/path/secret")).when(files).delete(name);
        var result=remove(a,OLD,"DELETE").andExpect(status().isServiceUnavailable()).andReturn();assertThat(result.getResponse().getContentAsString()).doesNotContain("private/path",name);
        assertThat(users.findById(id).orElseThrow().isDeletionPending()).isTrue();assertThat(Files.exists(storage.resolve(name))).isTrue();assertThat(tasks.existsById(task)).isTrue();
        mvc.perform(get("/api/documents").session(a)).andExpect(status().isConflict());
        mvc.perform(multipart("/api/documents/upload").file(new MockMultipartFile("file","TestRecord.pdf","application/pdf",PdfFixtures.pdf("More"))).session(a).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get("/api/auth/me").session(a)).andExpect(status().isOk()).andExpect(jsonPath("$.deletionPending").value(true));
        password(a,OLD,NEW,NEW).andExpect(status().isConflict());
        var retry=(MockHttpSession)login(email,OLD).andExpect(status().isOk()).andReturn().getRequest().getSession();
        doCallRealMethod().when(files).delete(name);remove(retry,OLD,"DELETE").andExpect(status().isNoContent());
        assertThat(users.existsById(id)).isFalse();assertThat(Files.exists(storage.resolve(name))).isFalse();assertThat(tasks.existsById(task)).isFalse();
    }
    @Autowired com.lifeadmin.service.DocumentService uploadService;
    @Autowired com.lifeadmin.service.AccountManagementService accountManagement;
    @Test void pendingAccountCannotUploadEvenIfRequestPassedSecurityBeforeDeletion() throws Exception {
        var a=account();var id=owner(a);var user=users.findById(id).orElseThrow();user.markDeletionPending();users.saveAndFlush(user);clearInvocations(files);
        assertThatThrownBy(()->uploadService.upload(id,new MockMultipartFile("file","TestRecord.pdf","application/pdf",PdfFixtures.pdf("Private"))))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("409");
        verify(files,never()).store(any());
    }
    @Test void deletionWaitsForInFlightUploadAndCleansItsFile() throws Exception {
        var a=account();var id=owner(a);var principal=new com.lifeadmin.security.AccountPrincipal(id,"unused@example.test","");
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation->{entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test timeout");return invocation.callRealMethod();}).when(files).store(any());
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var upload=executor.submit(()->uploadService.upload(id,new MockMultipartFile("file","TestRecord.pdf","application/pdf",PdfFixtures.pdf("Private"))));
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var deletion=executor.submit(()->accountManagement.delete(principal,new com.lifeadmin.dto.DeleteAccountRequest(OLD,"DELETE")));
            release.countDown();var result=upload.get(15,java.util.concurrent.TimeUnit.SECONDS);deletion.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(users.existsById(id)).isFalse();assertThat(documents.existsById(result.id())).isFalse();
            var stored=org.mockito.ArgumentCaptor.forClass(String.class);verify(files).delete(stored.capture());assertThat(Files.exists(storage.resolve(stored.getValue()))).isFalse();
        } finally {release.countDown();}
    }
    @Test void finalDatabaseFailureRetainsPendingAccountAndRetryCompletes() throws Exception {
        var a=account();var id=owner(a);var doc=upload(a);var name=documents.findById(doc).orElseThrow().getStoredFileName();
        jdbc.execute("CREATE FUNCTION reject_account_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'simulated failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_account_delete BEFORE DELETE ON users FOR EACH ROW EXECUTE FUNCTION reject_account_delete()");
        try {remove(a,OLD,"DELETE").andExpect(status().isServiceUnavailable());assertThat(users.findById(id).orElseThrow().isDeletionPending()).isTrue();assertThat(documents.existsById(doc)).isFalse();assertThat(Files.exists(storage.resolve(name))).isFalse();}
        finally {jdbc.execute("DROP TRIGGER reject_account_delete ON users");jdbc.execute("DROP FUNCTION reject_account_delete()");}
        remove(a,OLD,"DELETE").andExpect(status().isNoContent());assertThat(users.existsById(id)).isFalse();
    }
}
