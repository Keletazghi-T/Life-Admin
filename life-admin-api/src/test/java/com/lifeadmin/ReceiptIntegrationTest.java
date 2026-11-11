package com.lifeadmin;
import com.lifeadmin.repository.*;
import com.lifeadmin.service.*;
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
import java.io.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"app.ai.openai.api-key="}) @AutoConfigureMockMvc
class ReceiptIntegrationTest extends AccountTestSupport {
    static final EmbeddedPostgres postgres;static final Path storage;
    static {try{postgres=EmbeddedPostgres.builder().start();storage=Files.createTempDirectory("life-admin-receipt-test-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");r.add("app.storage-directory",storage::toString);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ReceiptRepository receipts;@Autowired JdbcTemplate jdbc;@Autowired ReceiptService receiptService;@Autowired AccountManagementService accounts;
    @MockitoSpyBean LocalDocumentStorage files;@MockitoBean DocumentAnalysisProvider provider;
    @AfterEach void noAi(){verifyNoInteractions(provider);}
    @AfterAll static void close() throws Exception {postgres.close();try(var paths=Files.walk(storage)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private UUID owner(MockHttpSession session){var c=(org.springframework.security.core.context.SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT");return ((com.lifeadmin.security.AccountPrincipal)c.getAuthentication().getPrincipal()).id();}
    private Map<String,Object> input(String merchant,String amount,String date,String category,boolean tax){var m=new HashMap<String,Object>();m.put("merchant",merchant);m.put("amount",amount);m.put("purchaseDate",date);m.put("category",category);m.put("taxRelevant",tax);m.put("description","Purchase record");m.put("notes","Private note");return m;}
    private Map<String,Object> standard(){return input("Office shop","12.34","2025-03-04","OFFICE",true);}
    private ResultActions create(MockHttpSession session,Map<String,Object> input,String name,String type,byte[] content) throws Exception {
        return mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file",name,type,content)).file(new MockMultipartFile("metadata","","application/json",json.writeValueAsBytes(input))).session(session).with(csrf()));
    }
    private UUID create(MockHttpSession session,Map<String,Object> input) throws Exception {return UUID.fromString(body(create(session,input,"receipt.pdf","application/pdf",PdfFixtures.pdf("Receipt")).andExpect(status().isCreated()).andReturn()).get("id").asText());}
    private JsonNode body(MvcResult result) throws Exception {return json.readTree(result.getResponse().getContentAsString());}
    private byte[] image(String format) throws Exception {var image=new java.awt.image.BufferedImage(3,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,format,out);return out.toByteArray();}
    private ResultActions edit(MockHttpSession session,UUID id,Map<String,Object> input) throws Exception {return mvc.perform(put("/api/receipts/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input)));}
    @Test void createsPdfPngAndJpegWithSafePrivateResponsesAndExactContent() throws Exception {
        var a=account();
        for(String ext:List.of("pdf","png","jpg")){
            String type=ext.equals("pdf")?"application/pdf":ext.equals("png")?"image/png":"image/jpeg";byte[] bytes=ext.equals("pdf")?PdfFixtures.pdf("Receipt"):image(ext);
            var result=create(a,standard(),"../../receipt."+ext,type,bytes).andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value("12.34")).andExpect(jsonPath("$.currency").value("USD")).andExpect(jsonPath("$.storedFileName").doesNotExist()).andExpect(jsonPath("$.userId").doesNotExist()).andReturn();
            var id=UUID.fromString(body(result).get("id").asText());var saved=receipts.findById(id).orElseThrow();assertThat(saved.getStoredFileName()).matches("[a-f0-9-]{36}\\."+ext);assertThat(Files.readAllBytes(storage.resolve(saved.getStoredFileName()))).isEqualTo(bytes);
            for(boolean download:List.of(true,false)){
                var response=mvc.perform(get("/api/receipts/"+id+"/content").param("download",String.valueOf(download)).session(a)).andExpect(status().isOk()).andExpect(content().contentType(type)).andExpect(content().bytes(bytes)).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().getResponse();
                assertThat(ContentDisposition.parse(response.getHeader("Content-Disposition")).getType()).isEqualTo(download?"attachment":"inline");assertThat(response.getHeader("Content-Disposition")).doesNotContain("../",saved.getStoredFileName());
            }
        }
    }
    @Test void invalidFileTypesSpoofedContentEmptyAndOversizedAreRejectedBeforeStorage() throws Exception {
        var a=account();clearInvocations(files);
        create(a,standard(),"receipt.svg","image/svg+xml","<svg/>".getBytes()).andExpect(status().isBadRequest());
        create(a,standard(),"receipt.png","image/png",PdfFixtures.pdf("Fake image")).andExpect(status().isBadRequest());
        create(a,standard(),"receipt.jpg","image/jpeg",image("png")).andExpect(status().isBadRequest());
        create(a,standard(),"receipt.pdf","image/png",PdfFixtures.pdf("Wrong MIME")).andExpect(status().isBadRequest());
        create(a,standard(),"receipt.pdf","application/pdf","%PDF-broken".getBytes()).andExpect(status().isBadRequest());
        create(a,standard(),"receipt.png","image/png",new byte[0]).andExpect(status().isBadRequest());
        create(a,standard(),"receipt.pdf","application/pdf",new byte[10*1024*1024+1]).andExpect(status().isPayloadTooLarge());verify(files,never()).store(any(),anyString());
    }
    @Test void editPersistsAllManualFieldsWithoutReplacingFile() throws Exception {
        var a=account();var id=create(a,standard());var before=receipts.findById(id).orElseThrow();
        var changed=input("Updated shop","100.20","2024-11-05","MEDICAL",false);changed.put("description","Updated description");changed.put("notes","Updated note");
        edit(a,id,changed).andExpect(status().isOk()).andExpect(jsonPath("$.merchant").value("Updated shop")).andExpect(jsonPath("$.amount").value("100.20")).andExpect(jsonPath("$.taxRelevant").value(false));
        mvc.perform(get("/api/receipts/"+id).session(a)).andExpect(jsonPath("$.notes").value("Updated note"));assertThat(receipts.findById(id).orElseThrow().getStoredFileName()).isEqualTo(before.getStoredFileName());
    }
    @Test void invalidAmountsDatesFieldsAndCurrencyCannotCreateOrEdit() throws Exception {
        var a=account();var id=create(a,standard());
        for(String amount:List.of("-1","0","12.345","1000000000000","not-money")){
            var input=standard();input.put("amount",amount);edit(a,id,input).andExpect(status().isBadRequest());create(a,input,"receipt.pdf","application/pdf",PdfFixtures.pdf("Invalid")).andExpect(status().isBadRequest());
        }
        for(String date:List.of("not-date","2025-02-30","1899-01-01",LocalDate.now().plusDays(2).toString())){var input=standard();input.put("purchaseDate",date);edit(a,id,input).andExpect(status().isBadRequest());}
        for(var entry:Map.of("merchant"," ","currency","EUR","category","UNKNOWN","notes","x".repeat(4001)).entrySet()){var input=standard();input.put(entry.getKey(),entry.getValue());edit(a,id,input).andExpect(status().isBadRequest());}
        var missing=standard();missing.remove("purchaseDate");edit(a,id,missing).andExpect(status().isBadRequest());assertThat(receipts.findById(id).orElseThrow().getAmount()).isEqualByComparingTo("12.34");
        var scientific=standard();scientific.put("amount","1e3");edit(a,id,scientific).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value("1000.00"));
    }
    @Test void searchYearCategorySortAndPaginationAreScopedAndLiteral() throws Exception {
        var a=account();var b=account();var low=create(a,input("100% shop","0.10","2025-01-01","OFFICE",true));var high=create(a,input("Alpha","90.50","2025-12-01","MEDICAL",true));create(a,input("Earlier","20","2024-12-01","OFFICE",false));create(b,input("100% secret","99","2025-01-01","OFFICE",true));
        for(String[] sort:List.of(new String[]{"newest",high.toString()},new String[]{"oldest",low.toString()},new String[]{"amount-high",high.toString()},new String[]{"amount-low",low.toString()}))mvc.perform(get("/api/receipts").session(a).param("year","2025").param("sort",sort[0])).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].id").value(sort[1]));
        mvc.perform(get("/api/receipts").session(a).param("search","%")).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(low.toString()));
        mvc.perform(get("/api/receipts").session(a).param("search","ALPHA")).andExpect(jsonPath("$.items[0].id").value(high.toString()));
        mvc.perform(get("/api/receipts").session(a).param("year","2025").param("category","OFFICE")).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/receipts").session(a).param("year","2024")).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/receipts").session(b)).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].merchant").value("100% secret"));
        for(int i=0;i<24;i++)create(a,input("Paged "+i,"1","2025-05-01","OTHER",false));
        mvc.perform(get("/api/receipts").session(a)).andExpect(jsonPath("$.items.length()").value(25)).andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(get("/api/receipts").session(a).param("page","1")).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.hasMore").value(false));
        for(var pair:Map.of("year","10000","sort","invalid","category","INVALID","page","-1").entrySet())mvc.perform(get("/api/receipts").session(a).param(pair.getKey(),pair.getValue())).andExpect(status().isBadRequest());
    }
    @Test void yearAndCategoryTotalsUseExactDecimalAndExcludeOtherUsersAndPendingRows() throws Exception {
        var a=account();create(a,input("A","0.10","2025-01-01","BUSINESS",true));create(a,input("B","0.20","2025-02-01","BUSINESS",true));create(a,input("C","1.05","2025-03-01",null,true));create(a,input("D","10.00","2025-04-01","MEDICAL",false));create(a,input("Previous","100","2024-12-31","BUSINESS",true));create(account(),input("Private","999","2025-01-01","BUSINESS",true));
        var id=create(a,input("Pending","500","2025-01-01","BUSINESS",true));jdbc.update("update receipts set status='DELETION_PENDING' where id=?",id);
        var response=body(mvc.perform(get("/api/receipts/summary").session(a).param("year","2025")).andExpect(status().isOk()).andExpect(jsonPath("$.receiptCount").value(4)).andExpect(jsonPath("$.taxRelevantCount").value(3)).andExpect(jsonPath("$.missingCategoryCount").value(1)).andExpect(jsonPath("$.missingInformationCount").value(0)).andReturn());
        assertThat(new BigDecimal(response.get("totalAmount").asText())).isEqualByComparingTo("11.35");assertThat(new BigDecimal(response.get("taxRelevantAmount").asText())).isEqualByComparingTo("1.35");assertThat(response.get("categories").get(0).get("amount").asText()).isEqualTo("0.30");
        mvc.perform(get("/api/receipts/summary").session(a).param("year","2024")).andExpect(jsonPath("$.receiptCount").value(1)).andExpect(jsonPath("$.taxRelevantAmount").value("100.00"));
        mvc.perform(get("/api/receipts/summary").session(account()).param("year","2025")).andExpect(jsonPath("$.receiptCount").value(0)).andExpect(jsonPath("$.categories").isEmpty());
    }
    @Test void authenticationCsrfAndBidirectionalOwnershipProtectEveryOperation() throws Exception {
        var a=account();var b=account();var idA=create(a,standard());var idB=create(b,standard());clearInvocations(files);
        for(var pair:List.of(Map.entry(a,idB),Map.entry(b,idA))){
            for(String suffix:List.of("","/content","/content?download=true"))mvc.perform(get("/api/receipts/"+pair.getValue()+suffix).session(pair.getKey())).andExpect(status().isNotFound());
            edit(pair.getKey(),pair.getValue(),standard()).andExpect(status().isNotFound());mvc.perform(delete("/api/receipts/"+pair.getValue()).session(pair.getKey()).with(csrf())).andExpect(status().isNotFound());
        }
        for(String path:List.of("/api/receipts","/api/receipts/summary","/api/receipts/"+idA,"/api/receipts/"+idA+"/content"))mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/receipts/"+idA).session(a)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/receipts/"+idA).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file","receipt.pdf","application/pdf",PdfFixtures.pdf("Private"))).with(csrf())).andExpect(status().isUnauthorized());
        verify(files,never()).read(anyString());verify(files,never()).delete(anyString());assertThat(receipts.existsById(idA)).isTrue();assertThat(receipts.existsById(idB)).isTrue();
    }
    @Test void missingReceiptAndMalformedIdReturnSafeErrors() throws Exception {
        var a=account();var id=UUID.randomUUID();mvc.perform(get("/api/receipts/"+id).session(a)).andExpect(status().isNotFound());edit(a,id,standard()).andExpect(status().isNotFound());mvc.perform(delete("/api/receipts/"+id).session(a).with(csrf())).andExpect(status().isNotFound());mvc.perform(get("/api/receipts/not-an-id").session(a)).andExpect(status().isBadRequest());
    }
    @Test void deletionRemovesFileAndRecordWithoutTouchingOtherAccount() throws Exception {
        var a=account();var b=account();var id=create(a,standard());var other=create(b,standard());var name=receipts.findById(id).orElseThrow().getStoredFileName();var otherName=receipts.findById(other).orElseThrow().getStoredFileName();
        mvc.perform(delete("/api/receipts/"+id).session(a).with(csrf())).andExpect(status().isNoContent());assertThat(receipts.existsById(id)).isFalse();assertThat(Files.exists(storage.resolve(name))).isFalse();assertThat(receipts.existsById(other)).isTrue();assertThat(Files.exists(storage.resolve(otherName))).isTrue();
    }
    @Test void cleanupFailureIsTrackedHiddenFromTotalsAndRetryable() throws Exception {
        var a=account();var id=create(a,standard());var name=receipts.findById(id).orElseThrow().getStoredFileName();doThrow(new IllegalStateException("private/path/secret")).when(files).delete(name);
        mvc.perform(delete("/api/receipts/"+id).session(a).with(csrf())).andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private/path"))));
        assertThat(receipts.findById(id).orElseThrow().getStatus()).isEqualTo("DELETION_PENDING");assertThat(Files.exists(storage.resolve(name))).isTrue();mvc.perform(get("/api/receipts/"+id+"/content").session(a)).andExpect(status().isNotFound());edit(a,id,standard()).andExpect(status().isNotFound());
        mvc.perform(get("/api/receipts/summary").session(a).param("year","2025")).andExpect(jsonPath("$.receiptCount").value(0));doCallRealMethod().when(files).delete(name);mvc.perform(delete("/api/receipts/"+id).session(a).with(csrf())).andExpect(status().isNoContent());assertThat(Files.exists(storage.resolve(name))).isFalse();
    }
    @Test void databaseFailureAfterFileDeletionRetainsMarkerAndCanBeRetried() throws Exception {
        var a=account();var id=create(a,standard());var name=receipts.findById(id).orElseThrow().getStoredFileName();
        jdbc.execute("CREATE FUNCTION reject_receipt_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");jdbc.execute("CREATE TRIGGER reject_receipt_delete BEFORE DELETE ON receipts FOR EACH ROW EXECUTE FUNCTION reject_receipt_delete()");
        try{mvc.perform(delete("/api/receipts/"+id).session(a).with(csrf())).andExpect(status().isServiceUnavailable());assertThat(receipts.findById(id).orElseThrow().getStatus()).isEqualTo("DELETION_PENDING");assertThat(Files.exists(storage.resolve(name))).isFalse();}finally{jdbc.execute("DROP TRIGGER reject_receipt_delete ON receipts");jdbc.execute("DROP FUNCTION reject_receipt_delete()");}
        mvc.perform(delete("/api/receipts/"+id).session(a).with(csrf())).andExpect(status().isNoContent());
    }
    @Test void accountDeletionIncludesReceiptFilesAndOtherUsersRemainUntouched() throws Exception {
        var a=account();var b=account();var owner=owner(a);var id=create(a,standard());var other=create(b,standard());var name=receipts.findById(id).orElseThrow().getStoredFileName();
        mvc.perform(delete("/api/account").session(a).with(csrf()).contentType("application/json").content("{\"currentPassword\":\"Fixture-only-password\",\"confirmation\":\"DELETE\"}")).andExpect(status().isNoContent());
        assertThat(users.existsById(owner)).isFalse();assertThat(receipts.existsById(id)).isFalse();assertThat(Files.exists(storage.resolve(name))).isFalse();assertThat(receipts.existsById(other)).isTrue();mvc.perform(get("/api/receipts/"+other).session(b)).andExpect(status().isOk());
    }
    @Test void accountDeletionFailureTracksReceiptThenRetryRemovesEverything() throws Exception {
        var a=account();var owner=owner(a);var id=create(a,standard());var name=receipts.findById(id).orElseThrow().getStoredFileName();doThrow(new IllegalStateException("unavailable")).when(files).delete(name);
        String body="{\"currentPassword\":\"Fixture-only-password\",\"confirmation\":\"DELETE\"}";
        mvc.perform(delete("/api/account").session(a).with(csrf()).contentType("application/json").content(body)).andExpect(status().isServiceUnavailable());assertThat(users.findById(owner).orElseThrow().isDeletionPending()).isTrue();assertThat(Files.exists(storage.resolve(name))).isTrue();mvc.perform(get("/api/receipts").session(a)).andExpect(status().isConflict());
        doCallRealMethod().when(files).delete(name);mvc.perform(delete("/api/account").session(a).with(csrf()).contentType("application/json").content(body)).andExpect(status().isNoContent());assertThat(users.existsById(owner)).isFalse();assertThat(Files.exists(storage.resolve(name))).isFalse();
    }
    @Test void pendingAccountCannotCreateEvenAfterSecurityCheckAndUploadFailureCleansFile() throws Exception {
        var a=account();var id=owner(a);var user=users.findById(id).orElseThrow();user.markDeletionPending();users.saveAndFlush(user);clearInvocations(files);
        var input=json.convertValue(standard(),com.lifeadmin.dto.ReceiptInput.class);
        assertThatThrownBy(()->receiptService.create(id,input,new MockMultipartFile("file","receipt.pdf","application/pdf",PdfFixtures.pdf("Receipt")))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("409");verify(files,never()).store(any(),anyString());
        var b=account();var snapshots=new HashSet<String>();try(var stream=Files.list(storage)){stream.forEach(p->snapshots.add(p.getFileName().toString()));}
        jdbc.execute("CREATE FUNCTION reject_receipt_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");jdbc.execute("CREATE TRIGGER reject_receipt_insert BEFORE INSERT ON receipts FOR EACH ROW EXECUTE FUNCTION reject_receipt_insert()");
        try{create(b,standard(),"receipt.pdf","application/pdf",PdfFixtures.pdf("Receipt")).andExpect(status().isInternalServerError());}finally{jdbc.execute("DROP TRIGGER reject_receipt_insert ON receipts");jdbc.execute("DROP FUNCTION reject_receipt_insert()");}
        try(var stream=Files.list(storage)){assertThat(stream.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet())).isEqualTo(snapshots);}
    }
}
