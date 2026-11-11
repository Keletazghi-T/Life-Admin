package com.lifeadmin;
import com.lifeadmin.entity.Document;
import com.lifeadmin.repository.DocumentRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"app.ai.openai.api-key="}) @AutoConfigureMockMvc
class DashboardIntegrationTest extends AccountTestSupport {
    static final EmbeddedPostgres postgres;
    static {try {postgres=EmbeddedPostgres.builder().start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
    }
    @AfterAll static void close() throws Exception {postgres.close();}
    @Autowired MockMvc mvc; @Autowired DocumentRepository documents; @Autowired JdbcTemplate jdbc;
    UUID owner(MockHttpSession session) {
        var context=(org.springframework.security.core.context.SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT");
        return ((com.lifeadmin.security.AccountPrincipal)context.getAuthentication().getPrincipal()).id();
    }
    Document document(MockHttpSession session,String name) {return documents.saveAndFlush(new Document(owner(session),name,UUID.randomUUID()+".pdf",10));}
    UUID task(Document doc,String title,LocalDate date,String status) {
        var id=UUID.randomUUID();jdbc.update("insert into tasks(id,document_id,title,due_date,status,development_mock,created_at,updated_at) values(?,?,?,?,?,false,now(),now())",id,doc.getId(),title,date,status);return id;
    }
    @Test void emptyDashboardIsValidAndRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard").session(account())).andExpect(status().isOk()).andExpect(jsonPath("$.documentCount").value(0)).andExpect(jsonPath("$.openTaskCount").value(0)).andExpect(jsonPath("$.completedTaskCount").value(0)).andExpect(jsonPath("$.upcomingDeadlineCount").value(0)).andExpect(jsonPath("$.recentDocuments").isEmpty()).andExpect(jsonPath("$.needsAttention").isEmpty()).andExpect(jsonPath("$.upcomingDeadlines").isEmpty());
    }
    @Test void countsAndDatesExcludeCompletedTasksAndOrderAttention() throws Exception {
        var a=account();var doc=document(a,"A.pdf");var today=LocalDate.now(ZoneOffset.UTC);
        task(doc,"Overdue",today.minusDays(1),"TODO");task(doc,"Today",today,"TODO");task(doc,"Soon",today.plusDays(1),"TODO");task(doc,"Last day",today.plusDays(30),"TODO");task(doc,"Later",today.plusDays(31),"TODO");task(doc,"No date",null,"TODO");
        task(doc,"Completed overdue",today.minusDays(2),"COMPLETED");task(doc,"Completed soon",today.plusDays(1),"COMPLETED");
        mvc.perform(get("/api/dashboard").session(a)).andExpect(status().isOk()).andExpect(jsonPath("$.documentCount").value(1)).andExpect(jsonPath("$.openTaskCount").value(6)).andExpect(jsonPath("$.completedTaskCount").value(2)).andExpect(jsonPath("$.upcomingDeadlineCount").value(2))
            .andExpect(jsonPath("$.needsAttention.length()").value(3)).andExpect(jsonPath("$.needsAttention[0].taskTitle").value("Overdue")).andExpect(jsonPath("$.needsAttention[1].taskTitle").value("Today")).andExpect(jsonPath("$.needsAttention[2].taskTitle").value("No date"))
            .andExpect(jsonPath("$.upcomingDeadlines.length()").value(2)).andExpect(jsonPath("$.upcomingDeadlines[0].taskTitle").value("Soon")).andExpect(jsonPath("$.upcomingDeadlines[1].taskTitle").value("Last day"));
    }
    @Test void recentAndTaskListsAreBoundedButCountsAreNotAndOwnershipIsBidirectional() throws Exception {
        var a=account();var b=account();var today=LocalDate.now(ZoneOffset.UTC);
        for(int i=0;i<8;i++) {var doc=document(a,"A-"+i+".pdf");jdbc.update("update documents set upload_date=? where id=?",java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i)),doc.getId());task(doc,"Future A",today.plusDays(1),"TODO");task(doc,"Overdue A",today.minusDays(1),"TODO");}
        var bDoc=document(b,"Private B.pdf");task(bDoc,"Private B task",today.plusDays(2),"TODO");
        mvc.perform(get("/api/dashboard").session(a)).andExpect(status().isOk()).andExpect(jsonPath("$.documentCount").value(8)).andExpect(jsonPath("$.openTaskCount").value(16)).andExpect(jsonPath("$.upcomingDeadlineCount").value(8)).andExpect(jsonPath("$.recentDocuments.length()").value(5)).andExpect(jsonPath("$.recentDocuments[0].displayName").value("A-7.pdf")).andExpect(jsonPath("$.recentDocuments[4].displayName").value("A-3.pdf")).andExpect(jsonPath("$.needsAttention.length()").value(5)).andExpect(jsonPath("$.upcomingDeadlines.length()").value(5)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Private B"))));
        mvc.perform(get("/api/dashboard").session(b)).andExpect(jsonPath("$.documentCount").value(1)).andExpect(jsonPath("$.openTaskCount").value(1)).andExpect(jsonPath("$.recentDocuments.length()").value(1)).andExpect(jsonPath("$.recentDocuments[0].displayName").value("Private B.pdf")).andExpect(jsonPath("$.needsAttention").isEmpty()).andExpect(jsonPath("$.upcomingDeadlines[0].taskTitle").value("Private B task"));
    }
    @Test void pendingDocumentRemainsVisibleForCleanupButItsTasksAreExcluded() throws Exception {
        var a=account();var doc=document(a,"Pending.pdf");task(doc,"Pending task",LocalDate.now(ZoneOffset.UTC).plusDays(1),"TODO");doc.markDeletionPending();documents.saveAndFlush(doc);
        mvc.perform(get("/api/dashboard").session(a)).andExpect(jsonPath("$.documentCount").value(1)).andExpect(jsonPath("$.openTaskCount").value(0)).andExpect(jsonPath("$.upcomingDeadlineCount").value(0)).andExpect(jsonPath("$.recentDocuments[0].status").value("DELETION_PENDING"));
    }
}
