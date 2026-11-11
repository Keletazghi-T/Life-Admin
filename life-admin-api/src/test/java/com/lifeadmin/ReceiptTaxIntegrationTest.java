package com.lifeadmin;
import com.lifeadmin.entity.*;
import com.lifeadmin.dto.ReceiptInput;
import com.lifeadmin.repository.ReceiptRepository;
import com.lifeadmin.service.LocalDocumentStorage;
import com.lifeadmin.ai.DocumentAnalysisProvider;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"app.ai.openai.api-key="}) @AutoConfigureMockMvc
class ReceiptTaxIntegrationTest extends AccountTestSupport {
    static final EmbeddedPostgres postgres;static final Path storage;
    static {try{postgres=EmbeddedPostgres.builder().start();storage=Files.createTempDirectory("life-admin-tax-test-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");r.add("app.storage-directory",storage::toString);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ReceiptRepository receipts;
    @MockitoSpyBean LocalDocumentStorage files;@MockitoBean DocumentAnalysisProvider provider;
    @AfterEach void noAi(){verifyNoInteractions(provider);}
    @AfterAll static void close() throws Exception {postgres.close();try(var paths=Files.walk(storage)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private UUID owner(MockHttpSession session){var c=(org.springframework.security.core.context.SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT");return ((com.lifeadmin.security.AccountPrincipal)c.getAuthentication().getPrincipal()).id();}
    private Receipt receipt(MockHttpSession session,String merchant,String amount,String date,ReceiptCategory category,boolean relevant) throws Exception {
        var bytes=PdfFixtures.pdf("Isolated tax test");var key=files.store(bytes);
        return receipts.saveAndFlush(new Receipt(owner(session),new ReceiptInput(merchant,LocalDate.parse(date),new BigDecimal(amount),"USD",category,"Description",relevant,"Notes"),"receipt.pdf",key,"application/pdf",bytes.length));
    }
    private JsonNode get(MockHttpSession a,String path) throws Exception {return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).session(a)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private byte[] csv(MockHttpSession a,int year,String scope) throws Exception {return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/receipts/tax/export").session(a).param("year",String.valueOf(year)).param("scope",scope)).andExpect(status().isOk()).andExpect(content().contentType("text/csv;charset=UTF-8")).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().string("Content-Disposition","attachment; filename=\"life-admin-receipts-"+year+".csv\"")).andReturn().getResponse().getContentAsByteArray();}
    // Independent CSV reader checks separators, quoted newlines and escaped quotes.
    private List<List<String>> parse(byte[] bytes){String csv=new String(bytes,StandardCharsets.UTF_8);assertThat(csv).startsWith("\uFEFF");csv=csv.substring(1);var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var cell=new StringBuilder();boolean quoted=false;
        for(int i=0;i<csv.length();i++){char c=csv.charAt(i);if(c=='"'){if(quoted&&i+1<csv.length()&&csv.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}else if(!quoted&&c==','){row.add(cell.toString());cell.setLength(0);}else if(!quoted&&c=='\r'&&i+1<csv.length()&&csv.charAt(i+1)=='\n'){row.add(cell.toString());rows.add(List.copyOf(row));row.clear();cell.setLength(0);i++;}else cell.append(c);}
        assertThat(quoted).isFalse();assertThat(cell).isEmpty();return rows;
    }
    @Test void exactLargeDecimalYearAndCategoryTotalsExcludeOtherAccountsAndPendingDeletion() throws Exception {
        var a=account();receipt(a,"Large","999999999999.99","2025-01-01",ReceiptCategory.BUSINESS,true);receipt(a,"Decimal A","0.10","2025-01-02",ReceiptCategory.BUSINESS,true);receipt(a,"Decimal B","0.20","2025-12-31",ReceiptCategory.MEDICAL,true);receipt(a,"Unflagged","10.01","2025-02-01",ReceiptCategory.OTHER,false);receipt(a,"Other year","50","2024-12-31",ReceiptCategory.BUSINESS,true);receipt(account(),"Private","700","2025-01-01",ReceiptCategory.BUSINESS,true);
        var pending=receipt(a,"Pending","999","2025-01-01",ReceiptCategory.BUSINESS,true);pending.markDeletionPending();receipts.saveAndFlush(pending);
        var result=get(a,"/api/receipts/tax?year=2025");var summary=result.get("summary");assertThat(summary.get("receiptCount").asInt()).isEqualTo(4);assertThat(summary.get("taxRelevantCount").asInt()).isEqualTo(3);assertThat(summary.get("totalAmount").asText()).isEqualTo("1000000000010.30");assertThat(summary.get("taxRelevantAmount").asText()).isEqualTo("1000000000000.29");assertThat(summary.get("categories").get(0).get("amount").asText()).isEqualTo("1000000000000.09");assertThat(result.get("recordsNeedingReview")).isEmpty();
        assertThat(get(a,"/api/receipts/tax?year=2024").get("summary").get("receiptCount").asInt()).isEqualTo(1);
        var exported=parse(csv(a,2025,"all"));assertThat(exported).hasSize(5);assertThat(exported.get(1).get(2)).isEqualTo("999999999999.99");
    }
    @Test void actualMissingCategoryMissingFileAndInvalidFileAreReportedWithoutMutatingData() throws Exception {
        var a=account();var uncategorized=receipt(a,"Uncategorized","1","2025-01-01",null,true);var missing=receipt(a,"Missing file","2","2025-01-02",ReceiptCategory.OTHER,false);var invalid=receipt(a,"Invalid file","3","2025-01-03",ReceiptCategory.OTHER,false);var valid=receipt(a,"Valid","4","2025-01-04",ReceiptCategory.OTHER,false);
        // Only isolated test storage is modified, never application/user files.
        Files.delete(storage.resolve(missing.getStoredFileName()));byte[] corrupt=Files.readAllBytes(storage.resolve(invalid.getStoredFileName()));corrupt[0]='X';Files.write(storage.resolve(invalid.getStoredFileName()),corrupt);
        var result=get(a,"/api/receipts/tax?year=2025").get("recordsNeedingReview");assertThat(result).hasSize(3);assertThat(result.get(0).get("reasons").toString()).contains("Missing category");assertThat(result.get(1).get("reasons").toString()).contains("Receipt file is missing");assertThat(result.get(2).get("reasons").toString()).contains("integrity check");
        assertThat(result.toString()).doesNotContain(storage.toString(),valid.getStoredFileName(),"Missing merchant","Missing purchase date","Missing amount");
        var rows=get(a,"/api/receipts/tax/records?year=2025&filter=needs-review");assertThat(rows.get("totalCount").asInt()).isEqualTo(3);assertThat(receipts.findById(uncategorized.getId()).orElseThrow().getUpdatedAt()).isEqualTo(uncategorized.getUpdatedAt());assertThat(receipts.count()).isGreaterThanOrEqualTo(4);assertThat(Files.readAllBytes(storage.resolve(invalid.getStoredFileName()))).isEqualTo(corrupt);
    }
    @Test void unavailableStorageIsReportedAsUnverifiedRatherThanInventedMissingData() throws Exception {
        var a=account();var r=receipt(a,"Temporary issue","1","2025-01-01",ReceiptCategory.OTHER,false);
        doReturn(com.lifeadmin.service.DocumentStorage.FileState.UNAVAILABLE).when(files).inspect(r.getStoredFileName(),r.getFileSize(),r.getContentType());
        assertThat(get(a,"/api/receipts/tax?year=2025").get("recordsNeedingReview").get(0).get("reasons").get(0).asText()).contains("could not be checked");
    }
    @Test void csvQuotesCommasQuotesNewlinesUnicodeAndNeutralizesSpreadsheetFormulas() throws Exception {
        var a=account();var first=receipt(a,"Café, \"Central\"","12.30","2025-02-01",ReceiptCategory.OFFICE,true);
        first.update(new ReceiptInput(first.getMerchant(),first.getPurchaseDate(),first.getAmount(),"USD",ReceiptCategory.OFFICE,"Line one\r\nLine two, \"quoted\"",true,"=HYPERLINK(\"https://example.invalid\")"));receipts.saveAndFlush(first);
        var data=parse(csv(a,2025,"all"));assertThat(data.get(0)).containsExactly("Purchase Date","Merchant","Amount","Currency","Category","Description","Potentially Tax Relevant","Notes","Original File Name");
        assertThat(data.get(1)).containsExactly("2025-02-01","Café, \"Central\"","12.30","USD","Office / Supplies","Line one\r\nLine two, \"quoted\"","Yes","'=HYPERLINK(\"https://example.invalid\")","receipt.pdf");
        for(String value:List.of("+1+1","-1+1","@SUM(1,2)"," \t=1+1","\u0001=1+1","\uFEFF=1+1")){
            first.update(new ReceiptInput(first.getMerchant(),first.getPurchaseDate(),first.getAmount(),"USD",first.getCategory(),"Description",true,value));receipts.saveAndFlush(first);assertThat(parse(csv(a,2025,"all")).get(1).get(7)).isEqualTo("'"+value);
        }
        assertThat(receipts.findById(first.getId()).orElseThrow().getNotes()).doesNotStartWith("'");
    }
    @Test void allAndFlaggedExportsAreYearScopedAndContainNoInternalIdentifiers() throws Exception {
        var a=account();var one=receipt(a,"Flagged","5","2025-01-01",ReceiptCategory.BUSINESS,true);receipt(a,"Not flagged","6","2025-01-02",null,false);receipt(a,"Previous","7","2024-01-01",ReceiptCategory.BUSINESS,true);var other=receipt(account(),"OTHER USER PRIVATE","99","2025-01-01",ReceiptCategory.BUSINESS,true);clearInvocations(files);
        var all=csv(a,2025,"all");assertThat(parse(all)).hasSize(3);assertThat(parse(csv(a,2025,"tax-relevant"))).hasSize(2);assertThat(new String(all,StandardCharsets.UTF_8)).doesNotContain(one.getId().toString(),owner(a).toString(),one.getStoredFileName(),other.getId().toString(),other.getStoredFileName(),"OTHER USER PRIVATE",storage.toString());verify(files,never()).read(anyString());verify(files,never()).inspect(anyString(),anyLong(),anyString());
    }
    @Test void authenticationAndBidirectionalAccountIsolationApplyToQueriesAndExport() throws Exception {
        var a=account();var b=account();var one=receipt(a,"A PRIVATE","1","2025-01-01",null,true);var two=receipt(b,"B PRIVATE","2","2025-01-01",null,true);
        for(String endpoint:List.of("/api/receipts/tax?year=2025","/api/receipts/tax/records?year=2025","/api/receipts/tax/export?year=2025")){
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(endpoint)).andExpect(status().isUnauthorized());
            var aBody=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(endpoint+"&userId="+owner(b)).session(a)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(aBody).doesNotContain("B PRIVATE",two.getStoredFileName());
            var bBody=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(endpoint).session(b)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(bBody).doesNotContain("A PRIVATE",one.getStoredFileName());
        }
    }
    @Test void recordsFilterSortAndPaginationAndInvalidRequests() throws Exception {
        var a=account();receipt(a,"Zebra","20","2025-01-01",ReceiptCategory.OFFICE,false);receipt(a,"Alpha","10","2025-02-01",ReceiptCategory.BUSINESS,true);
        for(String[] pair:List.of(new String[]{"date-asc","Zebra"},new String[]{"date-desc","Alpha"},new String[]{"merchant","Alpha"},new String[]{"category","Alpha"},new String[]{"amount-asc","Alpha"},new String[]{"amount-desc","Zebra"}))assertThat(get(a,"/api/receipts/tax/records?year=2025&sort="+pair[0]).get("items").get(0).get("receipt").get("merchant").asText()).isEqualTo(pair[1]);
        assertThat(get(a,"/api/receipts/tax/records?year=2025&filter=tax-relevant").get("totalCount").asInt()).isEqualTo(1);
        for(int i=0;i<25;i++)receipt(a,"Page "+i,"1","2025-01-01",ReceiptCategory.OTHER,false);
        var first=get(a,"/api/receipts/tax/records?year=2025");assertThat(first.get("items")).hasSize(25);assertThat(first.get("hasMore").asBoolean()).isTrue();assertThat(get(a,"/api/receipts/tax/records?year=2025&page=1").get("items")).hasSize(2);
        for(String query:List.of("year=10000","year=1899","year=invalid","year=2025&sort=bad","year=2025&filter=bad","year=2025&page=-1"))mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/receipts/tax/records?"+query).session(a)).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/receipts/tax/export?year=2025&scope=bad").session(a)).andExpect(status().isBadRequest());
    }
    @Test void emptyYearHasZeroSummaryNoReviewRecordsAndHeaderOnlyCsv() throws Exception {
        var a=account();var data=get(a,"/api/receipts/tax?year=2025");assertThat(data.get("summary").get("receiptCount").asInt()).isZero();assertThat(new BigDecimal(data.get("summary").get("totalAmount").asText())).isEqualByComparingTo(BigDecimal.ZERO);assertThat(data.get("summary").get("categories")).isEmpty();assertThat(data.get("recordsNeedingReview")).isEmpty();assertThat(get(a,"/api/receipts/tax/records?year=2025").get("items")).isEmpty();assertThat(parse(csv(a,2025,"all"))).hasSize(1);assertThat(parse(csv(a,2025,"tax-relevant"))).hasSize(1);
    }
}
