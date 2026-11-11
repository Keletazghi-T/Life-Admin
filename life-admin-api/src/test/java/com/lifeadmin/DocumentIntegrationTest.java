package com.lifeadmin;
import com.lifeadmin.repository.DocumentRepository;
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
@SpringBootTest(properties = "app.ai.openai.api-key=")
@AutoConfigureMockMvc
class DocumentIntegrationTest extends AccountTestSupport {
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
    @Autowired DocumentRepository repository;
    @Autowired ObjectMapper json;
    MockMultipartFile pdf(String name) throws Exception {
        return new MockMultipartFile("file", name, "application/pdf", PdfFixtures.pdf("Test fixture"));
    }
    @AfterAll static void cleanup() throws Exception {
        postgres.close();
        try (var files = Files.walk(storage)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
    @Test void uploadsStoresMetadataListsAndIsolatesSessions() throws Exception {
        var owner = account();
        var original = pdf("../../letter.pdf");
        var result = mvc.perform(multipart("/api/documents/upload").file(original)
                .session(owner).with(csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.originalFileName").value("letter.pdf"))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.storedFileName").doesNotExist()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        var saved = repository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(saved.getStoredFileName()).matches("[a-f0-9-]+\\.pdf");
        assertThat(Files.readAllBytes(storage.resolve(saved.getStoredFileName()))).isEqualTo(original.getBytes());
        mvc.perform(get("/api/documents").session(owner).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/documents/" + id).session(owner).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        var stranger = account();
        mvc.perform(get("/api/documents").session(stranger).with(csrf()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/documents/" + id).session(stranger).with(csrf()))
                .andExpect(status().isNotFound());
    }
    @Test void rejectsInvalidFilesAndMissingHeader() throws Exception {
        long count = repository.count();
        for (MockMultipartFile file : new MockMultipartFile[] {
            new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]),
            new MockMultipartFile("file", "fake.pdf", "application/pdf", "not a PDF".getBytes()),
            pdf("letter.exe"),
            new MockMultipartFile("file", "letter.pdf", "text/plain", "%PDF-1.7".getBytes())
        }) mvc.perform(multipart("/api/documents/upload").file(file).session(account()).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/documents/upload").file(new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[10 * 1024 * 1024 + 1]))
                .session(account()).with(csrf())).andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/documents/upload").file(pdf("file.pdf"))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/documents/upload").session(account()).with(csrf())).andExpect(status().isBadRequest());
        assertThat(repository.count()).isEqualTo(count);
    }
    @Test void rejectsInvalidPaginationAndIdentifiers() throws Exception {
        mvc.perform(get("/api/documents?page=-1").session(account()).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents/not-a-uuid").session(account()).with(csrf())).andExpect(status().isBadRequest());
    }
    @Test void missingAiKeyDoesNotPreventStartupOrUploadAndReturnsClearMessage() throws Exception {
        var owner = account();
        var uploaded = mvc.perform(multipart("/api/documents/upload")
                .file(new MockMultipartFile("file", "valid.pdf", "application/pdf", PdfFixtures.pdf("A sample letter.")))
                .session(owner).with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(uploaded.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/documents/" + id + "/analyze").session(owner).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("AI analysis is not configured")));
        mvc.perform(get("/api/documents/" + id + "/analysis").session(owner).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/" + id).session(owner).with(csrf()))
                .andExpect(status().isOk());
    }
    @Test void developmentMocksAreUnavailableByDefault() throws Exception {
        mvc.perform(get("/api/development/capabilities").session(account()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mockAnalysisEnabled").value(false));
        mvc.perform(post("/api/development/documents/" + UUID.randomUUID() + "/mock-analysis").session(account()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test void savedMockAnalysisCannotExposeDevelopmentGenerationWhenDisabled() throws Exception {
        var owner=account();
        var uploaded=mvc.perform(multipart("/api/documents/upload").file(pdf("TestRecord.pdf")).session(owner).with(csrf())).andExpect(status().isCreated()).andReturn();
        String id=json.readTree(uploaded.getResponse().getContentAsString()).get("id").asText();
        analysisRepository.saveAndFlush(new com.lifeadmin.entity.DocumentAnalysis(UUID.fromString(id),
            new com.lifeadmin.dto.AnalysisContent("Sample","Sample",true,null,java.util.List.of(),java.util.List.of("Review"),java.util.List.of()),
            com.lifeadmin.entity.AnalysisSource.DEVELOPMENT_MOCK));
        mvc.perform(post("/api/documents/"+id+"/tasks/generate").session(owner).with(csrf())).andExpect(status().isNotFound());
    }
    @Autowired com.lifeadmin.repository.DocumentAnalysisRepository analysisRepository;
}
