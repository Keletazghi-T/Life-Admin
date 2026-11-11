package com.lifeadmin;
import com.lifeadmin.repository.DocumentRepository;
import com.lifeadmin.repository.TaskRepository;
import com.lifeadmin.entity.DocumentAnalysis;
import org.springframework.http.MediaType;
import java.util.concurrent.*;
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
@SpringBootTest(properties = {"spring.profiles.active=dev", "app.development.mock-analysis-enabled=true", "app.ai.openai.api-key="})
@AutoConfigureMockMvc
class TaskIntegrationTest extends AccountTestSupport {
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
    @Autowired DocumentAnalysisRepository analyses;
    @MockitoBean DocumentAnalysisProvider provider;
    @MockitoSpyBean LocalDocumentStorage fileStorage;
    @Autowired TaskRepository tasks;
    private static final String HEADER = "X-Life-Admin";
    private static final String INPUT = "{\"title\":\"Find proof of income\",\"description\":\"Check records\",\"dueDate\":\"2026-10-15\"}";
    @AfterEach void noAiCalls() { verifyNoInteractions(provider); }
    @AfterAll static void cleanup() throws Exception {
        postgres.close();
        try (var files = Files.walk(storage)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
    private String upload(MockHttpSession owner, String filename) throws Exception {
        var result = mvc.perform(multipart("/api/documents/upload")
                .file(new MockMultipartFile("file", filename, "application/pdf", PdfFixtures.pdf("Development test document")))
                .session(owner).with(csrf())).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
    private String create(MockHttpSession owner, String documentId) throws Exception {
        var result = mvc.perform(post("/api/documents/" + documentId + "/tasks").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.status").value("TODO")).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
    private void mockAnalysis(MockHttpSession owner, String documentId) throws Exception {
        mvc.perform(post("/api/development/documents/" + documentId + "/mock-analysis").session(owner).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("DEVELOPMENT_MOCK"));
    }
    @Test void createsListsEditsCompletesReopensAndDeletesPersistentTasks() throws Exception {
        var owner = account();
        String doc = upload(owner, "TestRecord.pdf");
        String id = create(owner, doc);
        mvc.perform(get("/api/documents/" + doc + "/tasks").session(owner).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].dueDate").value("2026-10-15"));
        mvc.perform(put("/api/tasks/" + id).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":" Review proof ","description":" Updated details ","dueDate":null}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Review proof"))
                .andExpect(jsonPath("$.description").value("Updated details"))
                .andExpect(jsonPath("$.dueDate").value(org.hamcrest.Matchers.nullValue()));
        for (String state : List.of("COMPLETED", "TODO")) {
            mvc.perform(patch("/api/tasks/" + id + "/status").session(owner).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + state + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(state));
            assertThat(tasks.findById(UUID.fromString(id)).orElseThrow().getStatus().name()).isEqualTo(state);
            mvc.perform(get("/api/documents/" + doc + "/tasks").session(owner).with(csrf()))
                    .andExpect(jsonPath("$[0].status").value(state));
        }
        var saved = tasks.findById(UUID.fromString(id)).orElseThrow();
        assertThat(saved.getCreatedAt()).isNotNull(); assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(saved.getCreatedAt());
        assertThat(saved.getTitle()).isEqualTo("Review proof");
        mvc.perform(delete("/api/tasks/" + id).session(owner).with(csrf())).andExpect(status().isNoContent());
        assertThat(tasks.findById(UUID.fromString(id))).isEmpty();
        mvc.perform(get("/api/documents/" + doc + "/tasks").session(owner).with(csrf())).andExpect(content().json("[]"));
    }
    @Test void checksOwnershipOnEveryTaskAndDevelopmentOperation() throws Exception {
        var owner = account(); var stranger = account();
        String doc = upload(owner, "TestRecord.pdf"); String id = create(owner, doc);
        assertInaccessible(stranger, doc, id);
        assertThat(tasks.findById(UUID.fromString(id)).orElseThrow().getTitle()).isEqualTo("Find proof of income");
        assertThat(analyses.findByDocumentId(UUID.fromString(doc))).isEmpty();
    }
    @Test void missingDocumentsAndTasksReturn404() throws Exception {
        assertInaccessible(account(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }
    private void assertInaccessible(MockHttpSession owner, String doc, String id) throws Exception {
        mvc.perform(get("/api/documents/" + doc + "/tasks").session(owner).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/documents/" + doc + "/tasks").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(INPUT)).andExpect(status().isNotFound());
        mvc.perform(put("/api/tasks/" + id).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(INPUT)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/tasks/" + id).session(owner).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(patch("/api/tasks/" + id + "/status").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/documents/" + doc + "/tasks/generate").session(owner).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/development/documents/" + doc + "/mock-analysis").session(owner).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void validatesTitlesDescriptionsDatesIdsAndStatuses() throws Exception {
        var owner = account(); String doc = upload(owner, "TestRecord.pdf");
        for (String input : List.of("{}", "{\"title\":\"  \"}",
                json.writeValueAsString(java.util.Map.of("title", "a".repeat(1001))),
                json.writeValueAsString(java.util.Map.of("title", "Task", "description", "a".repeat(4001))),
                "{\"title\":\"Task\",\"dueDate\":\"2026-02-30\"}",
                "{\"title\":\"Task\",\"dueDate\":\"2026-10-15T00:00:00Z\"}",
                "{\"title\":\"Task\",\"dueDate\":\"0000-01-01\"}",
                "{\"title\":\"Task\",\"dueDate\":123}")) {
            mvc.perform(post("/api/documents/" + doc + "/tasks").session(owner).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest());
        }
        String id = create(owner, doc);
        for (String body : List.of("{}", "{\"status\":null}", "{\"status\":\"INVALID\"}"))
            mvc.perform(patch("/api/tasks/" + id + "/status").session(owner).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/tasks/" + id).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents/not-a-uuid/tasks").session(account()).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/tasks/not-a-uuid").session(account()).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents/" + doc + "/tasks")).andExpect(status().isUnauthorized());
        assertThat(tasks.findById(UUID.fromString(id)).orElseThrow().getTitle()).isEqualTo("Find proof of income");
    }
    @Test void permitsOptionalFieldsAndPastDueDatesWithoutInventingDates() throws Exception {
        var owner = account(); String doc = upload(owner, "TestRecord.pdf");
        mvc.perform(post("/api/documents/" + doc + "/tasks").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Manual task\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.dueDate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(post("/api/documents/" + doc + "/tasks").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Past task\",\"dueDate\":\"2020-02-29\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.dueDate").value("2020-02-29"));
    }
    @Test void mockAnalysisIsExplicitPersistentAndNeverOverwritesRealAnalysis() throws Exception {
        var owner = account(); String doc = upload(owner, "TestRecord.pdf");
        mvc.perform(get("/api/development/capabilities").session(owner).with(csrf()))
                .andExpect(jsonPath("$.mockAnalysisEnabled").value(true));
        mockAnalysis(owner, doc); mockAnalysis(owner, doc);
        mvc.perform(get("/api/documents/" + doc + "/analysis").session(owner).with(csrf()))
                .andExpect(jsonPath("$.source").value("DEVELOPMENT_MOCK"))
                .andExpect(jsonPath("$.deadline").value("2026-10-15"))
                .andExpect(jsonPath("$.nextSteps.length()").value(3));
        String real = upload(owner, "TestRecord.pdf");
        analyses.saveAndFlush(new DocumentAnalysis(UUID.fromString(real), new AnalysisContent("Real", "Saved analysis", null, null, List.of(), List.of(), List.of())));
        mvc.perform(post("/api/development/documents/" + real + "/mock-analysis").session(owner).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(analyses.findByDocumentId(UUID.fromString(real)).orElseThrow().getSummary()).isEqualTo("Saved analysis");
        String other = upload(owner, "another.pdf");
        mvc.perform(post("/api/development/documents/" + other + "/mock-analysis").session(owner).with(csrf()))
                .andExpect(status().isBadRequest());
    }
    @Test void generationUsesAnalysisAndDoesNotDuplicateOrUndoUserChanges() throws Exception {
        var owner = account(); String doc = upload(owner, "TestRecord.pdf"); mockAnalysis(owner, doc); confirmFixtureAnalysis(doc);
        String body = mvc.perform(post("/api/documents/" + doc + "/tasks/generate").session(owner).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].dueDate").value("2026-10-15"))
                .andExpect(jsonPath("$[0].developmentMock").value(true)).andReturn().getResponse().getContentAsString();
        String edited = json.readTree(body).get(0).get("id").asText();
        String deleted = json.readTree(body).get(1).get("id").asText();
        mvc.perform(post("/api/documents/" + doc + "/tasks/generate").session(owner).with(csrf()))
                .andExpect(content().json(body));
        mvc.perform(put("/api/tasks/" + edited).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Edited task\",\"dueDate\":null}")).andExpect(status().isOk());
        mvc.perform(patch("/api/tasks/" + edited + "/status").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COMPLETED\"}")).andExpect(status().isOk());
        mvc.perform(delete("/api/tasks/" + deleted).session(owner).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/api/documents/" + doc + "/tasks/generate").session(owner).with(csrf()))
                .andExpect(jsonPath("$.length()").value(2));
        var task = tasks.findById(UUID.fromString(edited)).orElseThrow();
        assertThat(task.getTitle()).isEqualTo("Edited task"); assertThat(task.getDueDate()).isNull();
        assertThat(task.getStatus().name()).isEqualTo("COMPLETED"); assertThat(tasks.findById(UUID.fromString(deleted))).isEmpty();
    }
    @Test void generationWorksForProviderNeutralAnalysisWithNoDeadline() throws Exception {
        var owner = account(); String doc = upload(owner, "notice.pdf");
        mvc.perform(post("/api/documents/" + doc + "/tasks/generate").session(owner).with(csrf()))
                .andExpect(status().isConflict());
        analyses.saveAndFlush(new DocumentAnalysis(UUID.fromString(doc), new AnalysisContent("Notice", "Summary", true, null,
                List.of(), List.of("Read original"), List.of())));
        confirmFixtureAnalysis(doc);
        mvc.perform(post("/api/documents/" + doc + "/tasks/generate").session(owner).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("Read original"))
                .andExpect(jsonPath("$[0].dueDate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].developmentMock").value(false));
    }
    @Test void concurrentGenerationCreatesOneSetOfTasks() throws Exception {
        var owner = account(); String doc = upload(owner, "TestRecord.pdf"); mockAnalysis(owner, doc); confirmFixtureAnalysis(doc);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> generate = () -> {
                start.await();
                return mvc.perform(post("/api/documents/" + doc + "/tasks/generate").session(owner).with(csrf()))
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(generate); var second = executor.submit(generate); start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(200);
        }
        mvc.perform(get("/api/documents/" + doc + "/tasks").session(owner).with(csrf()))
                .andExpect(jsonPath("$.length()").value(3));
    }

    private String futureTask(MockHttpSession owner, String doc, LocalDate due) throws Exception {
        var result=mvc.perform(post("/api/documents/"+doc+"/tasks").session(owner).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new com.lifeadmin.dto.TaskRequest("Reminder task",null,due))))
            .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
    private org.springframework.test.web.servlet.ResultActions reminder(MockHttpSession owner,String task,String body) throws Exception {
        return mvc.perform(post("/api/tasks/"+task+"/reminders").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private String createReminder(MockHttpSession owner,String task) throws Exception {
        var result=reminder(owner,task,"{\"daysBefore\":7}").andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
    @Test void reminderCrudAndStatusPersistWithoutChangingDates() throws Exception {
        var owner=account(); String doc=upload(owner,"TestRecord.pdf");
        LocalDate due=LocalDate.now(java.time.ZoneOffset.UTC).plusDays(30);
        String task=futureTask(owner,doc,due); String id=createReminder(owner,task);
        mvc.perform(get("/api/tasks/"+task+"/reminders").session(owner).with(csrf()))
            .andExpect(jsonPath("$[0].reminderDate").value(due.minusDays(7).toString())).andExpect(jsonPath("$[0].actionable").value(true));
        mvc.perform(put("/api/reminders/"+id).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reminderDate\":\""+due.minusDays(3)+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reminderDate").value(due.minusDays(3).toString()));
        for(String state:List.of("DISMISSED","PENDING")) {
            mvc.perform(patch("/api/reminders/"+id+"/status").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\""+state+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actionable").value(state.equals("PENDING")));
            mvc.perform(get("/api/tasks/"+task+"/reminders").session(owner).with(csrf()))
                .andExpect(jsonPath("$[0].status").value(state)).andExpect(jsonPath("$[0].reminderDate").value(due.minusDays(3).toString()));
        }
        mvc.perform(delete("/api/reminders/"+id).session(owner).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks/"+task+"/reminders").session(owner).with(csrf())).andExpect(content().json("[]"));
        createReminder(owner,task);
    }
    @Test void reminderValidationRejectsInvalidPastLateAndDuplicateDates() throws Exception {
        var owner=account(); String doc=upload(owner,"TestRecord.pdf");
        LocalDate today=LocalDate.now(java.time.ZoneOffset.UTC), due=today.plusDays(30);
        String task=futureTask(owner,doc,due); String id=createReminder(owner,task);
        reminder(owner,task,"{\"daysBefore\":7}").andExpect(status().isConflict());
        for(String body:List.of("{}","{\"daysBefore\":2}","{\"reminderDate\":\"2026-02-30\"}",
                "{\"reminderDate\":\"2026-10-01T00:00:00Z\"}","{\"reminderDate\":123}",
                "{\"reminderDate\":\""+due+"\",\"daysBefore\":1}",
                "{\"reminderDate\":\""+today.minusDays(1)+"\"}","{\"reminderDate\":\""+due.plusDays(1)+"\"}")) {
            reminder(owner,task,body).andExpect(status().isBadRequest());
        }
        reminder(owner,task,"{\"reminderDate\":\""+today+"\"}").andExpect(status().isCreated());
        reminder(owner,task,"{\"reminderDate\":\""+due+"\"}").andExpect(status().isCreated());
        reminder(owner,futureTask(owner,doc,null),"{\"daysBefore\":7}").andExpect(status().isConflict());
        reminder(owner,futureTask(owner,doc,today.plusDays(2)),"{\"daysBefore\":7}").andExpect(status().isBadRequest());
        mvc.perform(put("/api/reminders/"+id).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reminderDate\":\""+due+"\"}")).andExpect(status().isConflict());
        mvc.perform(patch("/api/reminders/"+id+"/status").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SENT\"}")).andExpect(status().isBadRequest());
    }
    @Test void reminderOwnershipAndMissingResourcesAreProtected() throws Exception {
        var owner=account(); String doc=upload(owner,"TestRecord.pdf");
        String task=futureTask(owner,doc,LocalDate.now(java.time.ZoneOffset.UTC).plusDays(30)); String id=createReminder(owner,task);
        for(String[] ids:List.of(new String[]{task,id},new String[]{UUID.randomUUID().toString(),UUID.randomUUID().toString()})) {
            var stranger=account();
            mvc.perform(get("/api/tasks/"+ids[0]+"/reminders").session(stranger).with(csrf())).andExpect(status().isNotFound());
            reminder(stranger,ids[0],"{\"daysBefore\":7}").andExpect(status().isNotFound());
            mvc.perform(put("/api/reminders/"+ids[1]).session(stranger).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"daysBefore\":1}")).andExpect(status().isNotFound());
            mvc.perform(delete("/api/reminders/"+ids[1]).session(stranger).with(csrf())).andExpect(status().isNotFound());
            mvc.perform(patch("/api/reminders/"+ids[1]+"/status").session(stranger).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\"}")).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/tasks/"+task+"/reminders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tasks/upcoming").session(account()).with(csrf())).andExpect(content().json("[]"));
    }
    @Test void completionAndDueDateChangesKeepReminderHistory() throws Exception {
        var owner=account(); String doc=upload(owner,"TestRecord.pdf");
        LocalDate due=LocalDate.now(java.time.ZoneOffset.UTC).plusDays(30);
        String task=futureTask(owner,doc,due); String id=createReminder(owner,task);
        for(String state:List.of("COMPLETED","TODO")) {
            mvc.perform(patch("/api/tasks/"+task+"/status").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\""+state+"\"}")).andExpect(status().isOk());
            mvc.perform(get("/api/tasks/"+task+"/reminders").session(owner).with(csrf()))
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].actionable").value(state.equals("TODO")));
            if(state.equals("COMPLETED")) reminder(owner,task,"{\"daysBefore\":1}").andExpect(status().isConflict());
        }
        mvc.perform(put("/api/tasks/"+task).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Updated\",\"dueDate\":null}")).andExpect(status().isOk());
        mvc.perform(get("/api/tasks/"+task+"/reminders").session(owner).with(csrf()))
            .andExpect(jsonPath("$[0].reminderDate").value(due.minusDays(7).toString())).andExpect(jsonPath("$[0].actionable").value(false));
        mvc.perform(delete("/api/tasks/"+task).session(owner).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/reminders/"+id).session(owner).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void upcomingIncludesOverdueAndTodaySortedAndExcludesCompletedUndatedAndOtherSessions() throws Exception {
        var owner=account(); String doc=upload(owner,"TestRecord.pdf"); LocalDate today=LocalDate.now(java.time.ZoneOffset.UTC);
        String later=futureTask(owner,doc,today.plusDays(5)), overdue=futureTask(owner,doc,today.minusDays(2)), now=futureTask(owner,doc,today);
        futureTask(owner,doc,null); String done=futureTask(owner,doc,today.minusDays(10));
        mvc.perform(patch("/api/tasks/"+done+"/status").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}")).andExpect(status().isOk());
        var stranger=account(); futureTask(stranger,upload(stranger,"Other.pdf"),today);
        mvc.perform(get("/api/tasks/upcoming").session(owner).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].taskId").value(overdue)).andExpect(jsonPath("$[1].taskId").value(now)).andExpect(jsonPath("$[2].taskId").value(later))
            .andExpect(jsonPath("$[0].documentId").value(doc)).andExpect(jsonPath("$[0].documentName").value("TestRecord.pdf"));
    }
    @Test void concurrentReminderCreationProducesOneReminder() throws Exception {
        var owner=account(); String doc=upload(owner,"TestRecord.pdf"); String task=futureTask(owner,doc,LocalDate.now(java.time.ZoneOffset.UTC).plusDays(30));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Integer> create=()->{ start.await(); return reminder(owner,task,"{\"daysBefore\":7}").andReturn().getResponse().getStatus(); };
            var a=executor.submit(create); var b=executor.submit(create); start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        mvc.perform(get("/api/tasks/"+task+"/reminders").session(owner).with(csrf())).andExpect(jsonPath("$.length()").value(1));
    }
}
