package com.lifeadmin;
import com.lifeadmin.entity.*;
import com.lifeadmin.dto.*;
import com.lifeadmin.repository.*;
import com.lifeadmin.service.LocalDocumentStorage;
import com.lifeadmin.ai.DocumentAnalysisProvider;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"app.ai.openai.api-key="}) @AutoConfigureMockMvc
class InboxIntegrationTest extends AccountTestSupport {
 static final EmbeddedPostgres postgres;static final Path storage;
 static {try{postgres=EmbeddedPostgres.builder().start();storage=Files.createTempDirectory("life-admin-inbox-test-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");r.add("app.storage-directory",storage::toString);}
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired DocumentRepository documents;@Autowired ReceiptRepository receipts;@Autowired DocumentAnalysisRepository analyses;@Autowired JdbcTemplate jdbc;@Autowired LocalDocumentStorage files;
 @MockitoBean DocumentAnalysisProvider provider;
 @AfterEach void noAi(){verifyNoInteractions(provider);}
 @AfterAll static void close()throws Exception{postgres.close();try(var paths=Files.walk(storage)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
 UUID owner(MockHttpSession s){var c=(org.springframework.security.core.context.SecurityContext)s.getAttribute("SPRING_SECURITY_CONTEXT");return ((com.lifeadmin.security.AccountPrincipal)c.getAuthentication().getPrincipal()).id();}
 Document doc(MockHttpSession s,String name){return documents.saveAndFlush(new Document(owner(s),name,UUID.randomUUID()+".pdf",10));}
 UUID task(Document doc,String title,LocalDate date,String status){var id=UUID.randomUUID();jdbc.update("insert into tasks(id,document_id,title,due_date,status,development_mock,created_at,updated_at) values(?,?,?,?,?,false,now(),now())",id,doc.getId(),title,date,status);return id;}
 UUID reminder(UUID task,LocalDate date,String status){var id=UUID.randomUUID();jdbc.update("insert into reminders(id,task_id,reminder_date,status,created_at,updated_at) values(?,?,?,?,now(),now())",id,task,date,status);return id;}
 Receipt receipt(MockHttpSession s,boolean reviewed,ReceiptCategory category)throws Exception{var bytes=PdfFixtures.pdf("Inbox receipt fixture");var input=reviewed?new ReceiptInput("Reviewed shop",LocalDate.of(2025,1,1),new BigDecimal("1.20"),"USD",category,"Purchase",false,""):null;return receipts.saveAndFlush(new Receipt(owner(s),input,"receipt.pdf",files.store(bytes),"application/pdf",bytes.length));}
 void analysis(Document d,Boolean action,LocalDate date){analyses.saveAndFlush(new DocumentAnalysis(d.getId(),new AnalysisContent("Notice","Review original",action,date,List.of(),List.of("Verify details"),List.of()),AnalysisSource.DEVELOPMENT_MOCK));}
 JsonNode read(MockHttpSession s,String path)throws Exception{return json.readTree(mvc.perform(get(path).session(s)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
 List<String> ids(JsonNode rows){var list=new ArrayList<String>();rows.forEach(r->list.add(r.get("id").asText()));return list;}
 @Test void emptyAccountIsCaughtUpAndEndpointsRequireAuthentication()throws Exception{
  for(String p:List.of("/api/dashboard","/api/inbox"))mvc.perform(get(p)).andExpect(status().isUnauthorized());var s=account();var dash=read(s,"/api/dashboard");for(String field:List.of("documentCount","receiptCount","openTaskCount","upcomingDeadlineCount","receiptsNeedingReviewCount","attentionCount"))assertThat(dash.get(field).asLong()).isZero();assertThat(dash.get("attention")).isEmpty();assertThat(dash.get("recentActivity")).isEmpty();assertThat(read(s,"/api/inbox").get("items")).isEmpty();
 }
 @Test void realCountsUrgencyAndNoDuplicatesAcrossTasksDocumentsAndReminders()throws Exception{
  var s=account();var today=LocalDate.now(ZoneOffset.UTC);var d=doc(s,"Letter");analysis(d,true,today.plusDays(1));doc(s,"Ordinary upload");
  var overdue=task(d,"Overdue",today.minusDays(1),"TODO");var now=task(d,"Today",today,"TODO");var soon=task(d,"Soon",today.plusDays(3),"TODO");task(d,"Far",today.plusDays(31),"TODO");task(d,"Undated",null,"TODO");var done=task(d,"Done",today.minusDays(5),"COMPLETED");reminder(done,today.minusDays(4),"PENDING");reminder(soon,today.plusDays(1),"PENDING");reminder(soon,today.plusDays(2),"PENDING");var r=receipt(s,false,null);receipt(s,true,ReceiptCategory.OTHER);
  var dashboard=read(s,"/api/dashboard");assertThat(dashboard.get("documentCount").asInt()).isEqualTo(2);assertThat(dashboard.get("receiptCount").asInt()).isEqualTo(2);assertThat(dashboard.get("openTaskCount").asInt()).isEqualTo(5);assertThat(dashboard.get("upcomingDeadlineCount").asInt()).isEqualTo(1);assertThat(dashboard.get("receiptsNeedingReviewCount").asInt()).isEqualTo(1);
  var items=read(s,"/api/inbox").get("items");assertThat(items).hasSize(6);assertThat(ids(items)).doesNotHaveDuplicates().startsWith("task:"+overdue,"task:"+now,"task:"+soon,"receipt:"+r.getId()).doesNotContain("document:"+d.getId(),"task:"+done);assertThat(items.get(0).get("status").asText()).isEqualTo("OVERDUE");assertThat(items.get(1).get("status").asText()).isEqualTo("DUE_TODAY");assertThat(items.get(2).get("dateLabel").asText()).isEqualTo("Reminder");assertThat(items.get(2).get("status").asText()).isEqualTo("UPCOMING");assertThat(items.get(3).get("reasons").size()).isEqualTo(5);
 }
 @Test void completedTasksAndReviewedReceiptsDisappearUnlessAnotherIssueRemains()throws Exception{
  var s=account();var d=doc(s,"Notice");analysis(d,true,null);var t=task(d,"Finish",null,"TODO");var r=receipt(s,false,null);
  mvc.perform(patch("/api/tasks/"+t+"/status").session(s).with(csrf()).contentType("application/json").content("{\"status\":\"COMPLETED\"}")).andExpect(status().isOk());
  String input="{\"merchant\":\"Confirmed\",\"purchaseDate\":\"2025-01-01\",\"amount\":\"2.00\",\"currency\":\"USD\"}";
  mvc.perform(put("/api/receipts/"+r.getId()).session(s).with(csrf()).contentType("application/json").content(input)).andExpect(status().isOk());
  var data=read(s,"/api/dashboard");assertThat(data.get("openTaskCount").asInt()).isZero();assertThat(data.get("receiptsNeedingReviewCount").asInt()).isZero();assertThat(data.get("attention")).hasSize(1);assertThat(data.get("attention").get(0).get("status").asText()).isEqualTo("MISSING_INFORMATION");assertThat(data.get("attention").get(0).get("reasons").toString()).contains("Missing category").doesNotContain("Receipt needs review");
  mvc.perform(put("/api/receipts/"+r.getId()).session(s).with(csrf()).contentType("application/json").content(input.replace("\"USD\"","\"USD\",\"category\":\"OTHER\""))).andExpect(status().isOk());assertThat(read(s,"/api/inbox").get("attentionCount").asInt()).isZero();
 }
 @Test void analysisOnlySurfacesActionableDocumentsAndSuggestedDeadlinesWithoutExistingPlans()throws Exception{
  var s=account();var today=LocalDate.now(ZoneOffset.UTC);var ordinary=doc(s,"Ordinary");var action=doc(s,"Action");analysis(action,true,null);var due=doc(s,"Deadline");analysis(due,true,today.plusDays(2));var none=doc(s,"No action");analysis(none,false,today.minusDays(1));var complete=doc(s,"Completed plan");analysis(complete,true,today.minusDays(1));task(complete,"Already done",null,"COMPLETED");
  var items=read(s,"/api/inbox").get("items");assertThat(ids(items)).containsExactly("document:"+due.getId(),"document:"+action.getId()).doesNotContain("document:"+ordinary.getId());assertThat(items.get(0).get("type").asText()).isEqualTo("DEADLINE");assertThat(items.get(0).get("developmentSample").asBoolean()).isTrue();assertThat(items.get(0).get("reasons").toString()).contains("verify any deadline");assertThat(read(s,"/api/dashboard").get("upcomingDeadlineCount").asInt()).isEqualTo(1);
 }
 @Test void filtersSortPaginationAndDashboardLimitHaveExactCounts()throws Exception{
  var s=account();var d=doc(s,"Tasks");var today=LocalDate.now(ZoneOffset.UTC);var old=task(d,"Old",today.minusDays(1),"TODO");jdbc.update("update tasks set created_at='2024-01-01T00:00:00Z' where id=?",old);for(int i=0;i<27;i++)task(d,"Task "+i,null,"TODO");receipt(s,false,null);var action=doc(s,"Review analysis");analysis(action,true,null);
  var dash=read(s,"/api/dashboard");assertThat(dash.get("attentionCount").asInt()).isEqualTo(30);assertThat(dash.get("attention")).hasSize(8);
  var first=read(s,"/api/inbox");var second=read(s,"/api/inbox?page=1");assertThat(first.get("items")).hasSize(25);assertThat(first.get("hasMore").asBoolean()).isTrue();assertThat(second.get("items")).hasSize(5);assertThat(second.get("hasMore").asBoolean()).isFalse();assertThat(ids(first.get("items"))).doesNotContainAnyElementsOf(ids(second.get("items")));
  for(var pair:Map.of("tasks",28,"receipts",1,"documents",1,"deadlines",1).entrySet())assertThat(read(s,"/api/inbox?filter="+pair.getKey()).get("totalCount").asInt()).isEqualTo(pair.getValue());assertThat(read(s,"/api/inbox?sort=newest").get("items").get(0).get("id").asText()).isNotEqualTo("task:"+old);
  for(String query:List.of("filter=email","sort=random","page=-1","page=1000001"))mvc.perform(get("/api/inbox?"+query).session(s)).andExpect(status().isBadRequest());
 }
 @Test void ownershipProtectsCountsAttentionActivityAndFiltersInBothDirections()throws Exception{
  var a=account();var b=account();for(var s:List.of(a,b)){String label=s==a?"Private A":"Private B";var d=doc(s,label);analysis(d,true,LocalDate.now(ZoneOffset.UTC));task(d,label+" task",LocalDate.now(ZoneOffset.UTC).minusDays(1),"TODO");var r=receipt(s,false,null);jdbc.update("update receipts set original_file_name=? where id=?",label+" receipt.pdf",r.getId());}
  for(var pair:List.of(Map.entry(a,"Private B"),Map.entry(b,"Private A"))){for(String path:List.of("/api/dashboard","/api/inbox","/api/inbox?filter=documents","/api/inbox?filter=receipts","/api/inbox?filter=tasks","/api/inbox?filter=deadlines"))assertThat(read(pair.getKey(),path+ (path.contains("?")?"&":"?")+"userId="+owner(pair.getKey()==a?b:a)).toString()).doesNotContain(pair.getValue());var dash=read(pair.getKey(),"/api/dashboard");assertThat(dash.get("documentCount").asInt()).isEqualTo(1);assertThat(dash.get("receiptCount").asInt()).isEqualTo(1);assertThat(dash.get("attentionCount").asInt()).isEqualTo(2);}
 }
 @Test void reminderDateDoesNotDuplicateTaskOrHideActualUpcomingDeadline()throws Exception{
  var s=account();var d=doc(s,"Reminders");var today=LocalDate.now(ZoneOffset.UTC);var t=task(d,"Due later",today.plusDays(5),"TODO");var pending=reminder(t,today.minusDays(1),"PENDING");reminder(t,today.plusDays(2),"PENDING");reminder(t,today.minusDays(10),"DISMISSED");
  var dash=read(s,"/api/dashboard");assertThat(dash.get("upcomingDeadlineCount").asInt()).isEqualTo(1);assertThat(dash.get("attention")).hasSize(1);assertThat(dash.get("attention").get(0).get("status").asText()).isEqualTo("OVERDUE");assertThat(dash.get("attention").get(0).get("date").asText()).isEqualTo(today.minusDays(1).toString());
  jdbc.update("update reminders set status='DISMISSED' where id=?",pending);assertThat(read(s,"/api/inbox").get("items").get(0).get("status").asText()).isEqualTo("UPCOMING");
 }
 @Test void activityIsBoundedScopedAndDerivedFromCurrentSavedTimestamps()throws Exception{
  var s=account();var d=doc(s,"Recent document");jdbc.update("update documents set upload_date='2024-01-01T00:00:00Z' where id=?",d.getId());var t=task(d,"Completed task",null,"COMPLETED");jdbc.update("update tasks set updated_at='2024-01-02T00:00:00Z' where id=?",t);var r=receipt(s,true,ReceiptCategory.OTHER);jdbc.update("update receipts set created_at='2024-01-03T00:00:00Z',updated_at='2024-01-04T00:00:00Z' where id=?",r.getId());
  var activity=read(s,"/api/dashboard").get("recentActivity");assertThat(ids(activity)).containsExactly("review:"+r.getId(),"receipt:"+r.getId(),"task:"+t,"document:"+d.getId());assertThat(activity.get(0).get("title").asText()).isEqualTo("Receipt reviewed");for(int i=0;i<7;i++)doc(s,"Extra "+i);assertThat(read(s,"/api/dashboard").get("recentActivity")).hasSize(5);
 }
 @Test void deletionPendingRecordsOfferCleanupWithoutResurfacingTheirTasks()throws Exception{
  var s=account();var d=doc(s,"Pending");task(d,"Hidden task",LocalDate.now(ZoneOffset.UTC).minusDays(1),"TODO");d.markDeletionPending();documents.saveAndFlush(d);var r=receipt(s,false,null);r.markDeletionPending();receipts.saveAndFlush(r);var dash=read(s,"/api/dashboard");assertThat(dash.get("openTaskCount").asInt()).isZero();assertThat(dash.get("receiptsNeedingReviewCount").asInt()).isZero();assertThat(dash.get("recentActivity")).isEmpty();assertThat(dash.get("attention")).hasSize(2);assertThat(dash.get("attention").toString()).contains("Retry cleanup").doesNotContain("Hidden task");
 }
}
