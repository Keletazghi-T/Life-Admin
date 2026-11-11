package com.lifeadmin;
import com.lifeadmin.entity.*;
import com.lifeadmin.dto.AnalysisContent;
import com.lifeadmin.repository.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.profiles.active=prod,dev","app.development.mock-analysis-enabled=true","app.ai.openai.api-key=","app.frontend-url=https://app.example.test","server.address=127.0.0.1"})
@AutoConfigureMockMvc
class ProductionConfigurationIntegrationTest extends AccountTestSupport {
    static final EmbeddedPostgres postgres;static final Path storage;
    static {try {postgres=EmbeddedPostgres.builder().start();storage=Files.createTempDirectory("life-admin-prod-test-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");r.add("app.storage-directory",storage::toString);
    }
    @Autowired MockMvc mvc; @Autowired DocumentRepository documents;@Autowired DocumentAnalysisRepository analyses;@Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @AfterAll static void close() throws Exception {postgres.close();try(var paths=Files.walk(storage)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    @Test void healthIsMinimalPublicAndProductionCookieIsSecureHttpOnlyStrict() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"UP\"}",true));
        var client=java.net.http.HttpClient.newHttpClient();var res=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/auth/csrf")).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding());
        assertThat(res.statusCode()).isEqualTo(200);assertThat(res.headers().firstValue("set-cookie").orElseThrow()).contains("Secure","HttpOnly","SameSite=Strict","Path=/");
        for(String endpoint:List.of("/actuator/env","/actuator/configprops","/actuator/heapdump","/api/documents"))mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
    }
    @Test void onlyExplicitOriginReceivesCredentialedCorsAndCsrfStillRequired() throws Exception {
        mvc.perform(options("/api/documents/upload").header("Origin","https://app.example.test").header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","content-type,x-csrf-token"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","https://app.example.test")).andExpect(header().string("Access-Control-Allow-Credentials","true"));
        for(String origin:List.of("https://evil.example.test","null","http://localhost:3000"))
            mvc.perform(options("/api/documents").header("Origin",origin).header("Access-Control-Request-Method","GET")).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(delete("/api/account").session(account()).header("Origin","https://app.example.test").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void prodOverridesDevFlagAndBlocksMockApiAndSavedSampleGeneration() throws Exception {
        var session=account();var context=(org.springframework.security.core.context.SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT");var owner=((com.lifeadmin.security.AccountPrincipal)context.getAuthentication().getPrincipal()).id();
        var doc=documents.saveAndFlush(new Document(owner,"TestRecord.pdf",UUID.randomUUID()+".pdf",10));
        analyses.saveAndFlush(new DocumentAnalysis(doc.getId(),new AnalysisContent("Sample","Sample",true,null,List.of(),List.of("Sample step"),List.of()),AnalysisSource.DEVELOPMENT_MOCK));
        mvc.perform(get("/api/development/capabilities").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.mockAnalysisEnabled").value(false));
        mvc.perform(post("/api/development/documents/"+doc.getId()+"/mock-analysis").session(session).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/documents/"+doc.getId()+"/tasks/generate").session(session).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void freshMigrationsAndUploadWorkWithoutAiConfiguration() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=true and version is not null",Integer.class)).isEqualTo(15);
        mvc.perform(multipart("/api/documents/upload").file(new MockMultipartFile("file","sample.pdf","application/pdf",PdfFixtures.pdf("Production profile test"))).session(account()).with(csrf())).andExpect(status().isCreated()).andExpect(jsonPath("$.storedFileName").doesNotExist());
        mvc.perform(get("/api/documents/not-an-id").session(account())).andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))));
    }
    @Test void realLocalExtractionWorksInProductionWithoutDevelopmentSamples() throws Exception {
        var session=account();
        var response=mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file","receipt.pdf","application/pdf",PdfFixtures.pdf("Receipt"))).session(session).with(csrf())).andExpect(status().isCreated()).andReturn();
        var id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/receipts/extraction-capabilities").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.extractionEnabled").value(true));
        mvc.perform(post("/api/receipts/"+id+"/extract").session(session).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.source").value("PDF_TEXT"));
        assertThat(jdbc.queryForObject("select review_status from receipts where id=?",String.class,UUID.fromString(id))).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("select extraction_details from receipts where id=?",String.class,UUID.fromString(id))).isNull();
    }

}
