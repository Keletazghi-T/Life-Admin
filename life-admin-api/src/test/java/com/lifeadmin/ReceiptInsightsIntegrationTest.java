package com.lifeadmin;

import com.lifeadmin.ai.*;
import com.lifeadmin.dto.ReceiptInput;
import com.lifeadmin.entity.*;
import com.lifeadmin.repository.ReceiptRepository;
import com.fasterxml.jackson.databind.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.ai.openai.api-key="}) @AutoConfigureMockMvc
class ReceiptInsightsIntegrationTest extends AccountTestSupport {
    static final EmbeddedPostgres postgres;
    static final Path storage;
    static {try {postgres=EmbeddedPostgres.builder().start();storage=Files.createTempDirectory("life-admin-insights-test-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");r.add("app.storage-directory",storage::toString);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired ReceiptRepository receipts; @Autowired JdbcTemplate jdbc;
    @MockitoBean OpenAiReceiptExtractionProvider provider; @MockitoBean DocumentAnalysisProvider documentProvider;
    @AfterEach void noAi() {verifyNoInteractions(provider,documentProvider);}
    @AfterAll static void close() throws Exception {postgres.close();Files.delete(storage);}
    private UUID owner(MockHttpSession session) {var c=(org.springframework.security.core.context.SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT");return ((com.lifeadmin.security.AccountPrincipal)c.getAuthentication().getPrincipal()).id();}
    private Receipt receipt(MockHttpSession session,String merchant,String amount,String date,ReceiptCategory category) {
        return receipts.saveAndFlush(new Receipt(owner(session),new ReceiptInput(merchant,LocalDate.parse(date),new BigDecimal(amount),"USD",category,null,false,null),"fixture.pdf",UUID.randomUUID()+".pdf","application/pdf",10));
    }
    private JsonNode insights(MockHttpSession session,String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/receipts/insights"+query).session(session)).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString());
    }
    @Test void confirmedTotalsCategoriesMerchantsMonthsAndAverageAreExact() throws Exception {
        var a=account();receipt(a,"Cafe","10.10","2025-01-01",ReceiptCategory.MEALS);receipt(a,"Cafe","20.20","2025-02-28",ReceiptCategory.MEALS);receipt(a,"Office store","4.00","2025-12-31",ReceiptCategory.OFFICE);receipt(a,"Unknown category","1.00","2025-01-01",null);
        var data=insights(a,"?year=2025");assertThat(data.path("receiptCount").asInt()).isEqualTo(4);assertThat(data.path("totalAmount").asText()).isEqualTo("35.30");assertThat(data.path("averageAmount").asText()).isEqualTo("8.83");
        assertThat(data.path("categories").get(0).path("name").asText()).isEqualTo("MEALS");assertThat(data.path("categories").get(0).path("amount").asText()).isEqualTo("30.30");assertThat(data.path("categories").get(2).path("name").isNull()).isTrue();
        var merchant=data.path("merchants").get(0);assertThat(merchant.path("name").asText()).isEqualTo("Cafe");assertThat(merchant.path("count").asInt()).isEqualTo(2);assertThat(merchant.path("amount").asText()).isEqualTo("30.30");
        assertThat(data.path("months")).hasSize(12);for(int i=0;i<12;i++)assertThat(data.path("months").get(i).path("month").asInt()).isEqualTo(i+1);
        assertThat(data.path("months").get(0).path("amount").asText()).isEqualTo("11.10");assertThat(data.path("months").get(1).path("amount").asText()).isEqualTo("20.20");assertThat(data.path("months").get(2).path("amount").asText()).isEqualTo("0.00");assertThat(data.path("months").get(11).path("amount").asText()).isEqualTo("4.00");
    }
    @Test void excludesUnreviewedMissingAmountsSuggestionsAndPendingDeletionWithoutMutation() throws Exception {
        var a=account();var confirmed=receipt(a,"Confirmed","2","2025-01-01",ReceiptCategory.OTHER);
        var draft=receipt(a,"Unconfirmed","900","2025-01-01",ReceiptCategory.MEALS);
        jdbc.update("update receipts set review_status='NEEDS_REVIEW',suggested_amount=9999,suggested_merchant='Suggestion' where id=?",draft.getId());
        var missing=receipts.saveAndFlush(new Receipt(owner(a),null,"missing.pdf",UUID.randomUUID()+".pdf","application/pdf",10));
        jdbc.update("update receipts set purchase_date='2025-01-01',suggested_amount=777 where id=?",missing.getId());
        var pending=receipt(a,"Pending","100","2025-01-01",null);pending.markDeletionPending();receipts.saveAndFlush(pending);
        var before=jdbc.queryForList("select * from receipts where user_id=? order by id",owner(a));
        var data=insights(a,"?year=2025");assertThat(data.path("receiptCount").asInt()).isEqualTo(1);assertThat(data.path("totalAmount").asText()).isEqualTo("2.00");assertThat(data.toString()).doesNotContain("Unconfirmed","Suggestion","Pending");
        assertThat(jdbc.queryForList("select * from receipts where user_id=? order by id",owner(a))).isEqualTo(before);
        assertThat(receipts.findById(confirmed.getId()).orElseThrow().getReviewStatus()).isEqualTo(ReceiptReviewStatus.REVIEWED);
    }
    @Test void yearsAndQueriesAreAccountScopedAndAuthenticationIsRequired() throws Exception {
        var a=account();var b=account();receipt(a,"A shop","5","2025-01-01",ReceiptCategory.MEALS);receipt(b,"B private","999","2024-01-01",ReceiptCategory.OFFICE);
        mvc.perform(get("/api/receipts/insights")).andExpect(status().isUnauthorized());
        var data=insights(a,"?year=2025&userId="+owner(b));assertThat(data.toString()).doesNotContain("B private","2024");assertThat(data.path("totalAmount").asText()).isEqualTo("5.00");
        assertThat(insights(b,"?year=2025").path("receiptCount").asInt()).isZero();assertThat(insights(b,"?year=2024").path("totalAmount").asText()).isEqualTo("999.00");
    }
    @Test void currentYearDefaultYearBoundariesAndEmptyYear() throws Exception {
        var a=account();int year=LocalDate.now(ZoneOffset.UTC).getYear();receipt(a,"Current","1",year+"-01-01",null);receipt(a,"Previous","2",(year-1)+"-12-31",null);
        assertThat(insights(a,"").path("totalAmount").asText()).isEqualTo("1.00");assertThat(insights(a,"?year="+(year-1)).path("totalAmount").asText()).isEqualTo("2.00");
        var zero=insights(a,"?year=1900");assertThat(zero.path("receiptCount").asInt()).isZero();assertThat(zero.path("totalAmount").asText()).isEqualTo("0.00");assertThat(zero.path("averageAmount").asText()).isEqualTo("0.00");assertThat(zero.path("categories")).isEmpty();assertThat(zero.path("merchants")).isEmpty();assertThat(zero.path("months")).hasSize(12);for(var month:zero.path("months"))assertThat(month.path("amount").asText()).isEqualTo("0.00");
        for(String invalid:List.of("1899","10000","bad"))mvc.perform(get("/api/receipts/insights?year="+invalid).session(a)).andExpect(status().isBadRequest());
    }
    @Test void insightsIncludeAllReceiptsBeyondListPageSizeAndReflectConfirmedEdits() throws Exception {
        var a=account();for(int i=0;i<31;i++)receipt(a,"Shop","0.10","2025-01-01",ReceiptCategory.OTHER);
        var edited=receipt(a,"Old merchant","1","2025-02-01",ReceiptCategory.MEALS);edited.update(new ReceiptInput("New merchant",LocalDate.of(2025,3,1),new BigDecimal("2.00"),"USD",ReceiptCategory.OFFICE,null,false,null));receipts.saveAndFlush(edited);
        var data=insights(a,"?year=2025&page=99");assertThat(data.path("receiptCount").asInt()).isEqualTo(32);assertThat(data.path("totalAmount").asText()).isEqualTo("5.10");assertThat(data.path("averageAmount").asText()).isEqualTo("0.16");assertThat(data.toString()).doesNotContain("Old merchant");assertThat(data.path("months").get(2).path("amount").asText()).isEqualTo("2.00");
    }
}
