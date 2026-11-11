package com.lifeadmin;
import com.lifeadmin.repository.*;
import com.lifeadmin.ai.DocumentAnalysisProvider;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.profiles.active=dev","app.development.mock-analysis-enabled=true","app.ai.openai.api-key="})
@AutoConfigureMockMvc
class AuthIntegrationTest {
    static final EmbeddedPostgres postgres;
    static final Path storage;
    static {
        try { postgres=EmbeddedPostgres.builder().start(); storage=Files.createTempDirectory("life-admin-auth-test-"); }
        catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres"); r.add("spring.datasource.password",()->"postgres"); r.add("app.storage-directory",storage::toString);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired UserRepository users;
    @Autowired DocumentRepository documents; @Autowired PasswordEncoder passwords;
    @MockitoBean DocumentAnalysisProvider provider;
    static final String PASSWORD="Example-test-password!";
    @AfterEach void noAi() { verifyNoInteractions(provider); }
    @AfterAll static void cleanup() throws Exception {
        postgres.close(); try(var files=Files.walk(storage)) { for(Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private String email() { return UUID.randomUUID()+"@example.test"; }
    private String credentials(String email,String password) throws Exception { return json.writeValueAsString(Map.of("email",email,"password",password)); }
    private String token(MockHttpSession session) throws Exception {
        return body(mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn()).get("token").asText();
    }
    private ResultActions mutate(MockHttpSession session,MockHttpServletRequestBuilder request) throws Exception {
        if (!(request instanceof org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder)) request.contentType(MediaType.APPLICATION_JSON);
        return mvc.perform(request.session(session).header("X-CSRF-TOKEN",token(session)));
    }
    private MockHttpSession register(String email) throws Exception {
        var session=new MockHttpSession();
        mutate(session,post("/api/auth/register").content(credentials(email,PASSWORD))).andExpect(status().isCreated());
        return session;
    }
    @Test void registrationNormalizesAndHashesPasswordAndAuthenticates() throws Exception {
        String email=email(); var session=new MockHttpSession(); String oldId=session.getId(); String oldToken=token(session);
        var result=mvc.perform(post("/api/auth/register").session(session).header("X-CSRF-TOKEN",oldToken).contentType(MediaType.APPLICATION_JSON)
            .content(credentials("  "+email.toUpperCase(Locale.ROOT)+"  ",PASSWORD))).andExpect(status().isCreated()).andReturn();
        assertThat(session.getId()).isNotEqualTo(oldId);
        assertThat(body(result).size()).isEqualTo(2); assertThat(body(result).get("email").asText()).isEqualTo(email);
        var saved=users.findByEmail(email).orElseThrow(); assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD,saved.getPasswordHash())).isTrue();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(saved.getId().toString()))
            .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN",oldToken)).andExpect(status().isForbidden());
        var context=(org.springframework.security.core.context.SecurityContext)session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(((org.springframework.security.core.userdetails.UserDetails)context.getAuthentication().getPrincipal()).getPassword()).isNull();
    }
    @Test void duplicateAndInvalidRegistrationAreRejected() throws Exception {
        String email=email(); register(email);
        mutate(new MockHttpSession(),post("/api/auth/register").content(credentials(email.toUpperCase(Locale.ROOT),PASSWORD))).andExpect(status().isConflict());
        for(String input:List.of("{}",credentials("invalid",PASSWORD),credentials(email(),"short"),credentials(email(),"        "),credentials(email(),"é".repeat(37)))) {
            mutate(new MockHttpSession(),post("/api/auth/register").content(input)).andExpect(status().isBadRequest());
        }
    }
    @Test void loginLogoutAndFreshSessionRestorePersistentOwnership() throws Exception {
        String email=email(); MockHttpSession first=register(email);
        var upload=mutate(first,multipart("/api/documents/upload").file(new MockMultipartFile("file","TestRecord.pdf","application/pdf",PdfFixtures.pdf("Account document"))))
            .andExpect(status().isCreated()).andReturn();
        String doc=body(upload).get("id").asText();
        mutate(first,post("/api/auth/logout")).andExpect(status().isNoContent()); assertThat(first.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        var second=new MockHttpSession(); String oldId=second.getId();
        mutate(second,post("/api/auth/login").content(credentials(email.toUpperCase(Locale.ROOT),PASSWORD))).andExpect(status().isOk());
        assertThat(second.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/documents/"+doc).session(second)).andExpect(status().isOk());
        assertThat(documents.findById(UUID.fromString(doc)).orElseThrow().getUserId()).isEqualTo(users.findByEmail(email).orElseThrow().getId());
        mutate(second,post("/api/auth/logout")).andExpect(status().isNoContent());
        mvc.perform(get("/api/documents/"+doc)).andExpect(status().isUnauthorized());
    }
    @Test void badLoginUsesGenericErrorAndNoAuthentication() throws Exception {
        String email=email(); register(email);
        for(String address:List.of(email,email())) {
            var session=new MockHttpSession();
            mutate(session,post("/api/auth/login").content(credentials(address,"wrong-password")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid email or password."));
            mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        }
    }
    @Test void csrfProtectsRegistrationLoginLogoutAndMutations() throws Exception {
        for(String endpoint:List.of("/api/auth/register","/api/auth/login")) {
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(credentials(email(),PASSWORD))).andExpect(status().isForbidden());
        }
        var session=register(email());
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/documents/"+UUID.randomUUID()+"/tasks").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Test\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN","invalid")).andExpect(status().isForbidden());
        mvc.perform(options("/api/documents").header("Origin","https://other.example").header("Access-Control-Request-Method","POST"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
    @Test void allDataRequiresAuthenticationAndStorageIsNotPublic() throws Exception {
        for(String path:List.of("/api/auth/me","/api/documents","/api/tasks/upcoming","/api/development/capabilities",
            "/api/documents/"+UUID.randomUUID(),"/api/tasks/"+UUID.randomUUID()+"/reminders")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        var session=register(email());
        mvc.perform(get("/uploads/guessed.pdf").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/documents").session(session)).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
    }
    @Test void realAccountsCannotReadOrMutateEachOthersData() throws Exception {
        var a=register(email()); var b=register(email());
        String doc=body(mutate(a,multipart("/api/documents/upload").file(new MockMultipartFile("file","TestRecord.pdf","application/pdf",PdfFixtures.pdf("Private"))))
            .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mutate(a,post("/api/development/documents/"+doc+"/mock-analysis")).andExpect(status().isOk());
        String task=body(mutate(a,post("/api/documents/"+doc+"/tasks").content("{\"title\":\"Private task\",\"dueDate\":\""+LocalDate.now(ZoneOffset.UTC).plusDays(30)+"\"}"))
            .andExpect(status().isCreated()).andReturn()).get("id").asText();
        String reminder=body(mutate(a,post("/api/tasks/"+task+"/reminders").content("{\"daysBefore\":7}"))
            .andExpect(status().isCreated()).andReturn()).get("id").asText();
        for(String path:List.of("/api/documents/"+doc,"/api/documents/"+doc+"/analysis","/api/documents/"+doc+"/tasks","/api/tasks/"+task+"/reminders")) {
            mvc.perform(get(path).session(b)).andExpect(status().isNotFound());
        }
        for(String path:List.of("/api/documents/"+doc+"/analyze","/api/development/documents/"+doc+"/mock-analysis","/api/documents/"+doc+"/tasks/generate")) {
            mutate(b,post(path)).andExpect(status().isNotFound());
        }
        mutate(b,put("/api/tasks/"+task).content("{\"title\":\"Attack\"}")).andExpect(status().isNotFound());
        mutate(b,patch("/api/tasks/"+task+"/status").content("{\"status\":\"COMPLETED\"}")).andExpect(status().isNotFound());
        mutate(b,delete("/api/tasks/"+task)).andExpect(status().isNotFound());
        mutate(b,post("/api/tasks/"+task+"/reminders").content("{\"daysBefore\":3}")).andExpect(status().isNotFound());
        mutate(b,put("/api/reminders/"+reminder).content("{\"daysBefore\":3}")).andExpect(status().isNotFound());
        mutate(b,patch("/api/reminders/"+reminder+"/status").content("{\"status\":\"DISMISSED\"}")).andExpect(status().isNotFound());
        mutate(b,delete("/api/reminders/"+reminder)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/upcoming").session(b)).andExpect(content().json("[]"));
        mvc.perform(get("/api/documents").session(b)).andExpect(content().json("[]"));
        mvc.perform(get("/api/tasks/upcoming").session(a)).andExpect(jsonPath("$[0].taskId").value(task));
        mvc.perform(get("/api/tasks/"+task+"/reminders").session(a)).andExpect(jsonPath("$[0].status").value("PENDING"));
    }
}
