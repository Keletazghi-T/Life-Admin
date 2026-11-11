package com.lifeadmin;

import com.lifeadmin.ai.OpenAiReceiptExtractionProvider;
import com.lifeadmin.dto.ReceiptExtractionDetails;
import com.lifeadmin.entity.ReceiptCategory;
import com.lifeadmin.repository.ReceiptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="app.ai.openai.api-key=") @AutoConfigureMockMvc
class ReceiptAiIntegrationTest extends AccountTestSupport {
    static final EmbeddedPostgres postgres;
    static final Path storage;
    static { try { postgres=EmbeddedPostgres.builder().start();storage=Files.createTempDirectory("receipt-ai-test-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");r.add("app.storage-directory",storage::toString);
    }
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.lifeadmin.service.ReceiptExtractionWorkflow workflow;
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired ReceiptRepository receipts;
    @MockitoBean OpenAiReceiptExtractionProvider images;
    @AfterAll static void close() throws Exception {postgres.close();try(var paths=Files.walk(storage)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private ReceiptExtractionDetails suggestion() {
        return new ReceiptExtractionDetails("Oak Cafe", LocalDate.parse("2025-09-20"), new BigDecimal("6.72"), "USD", ReceiptCategory.MEALS, "Coffee and breakfast", false, "OPENAI_IMAGE", "Mocked response for test only", "EXTRACTED", "OPENAI_IMAGE", null, null, "fixture-model");
    }
    private byte[] image() throws Exception {var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(3,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    private UUID upload(MockHttpSession session) throws Exception {
        return UUID.fromString(json.readTree(mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file","test.png","image/png",image())).session(session).with(csrf())).andExpect(status().isCreated()).andExpect(jsonPath("$.reviewStatus").value("NEEDS_REVIEW")).andReturn().getResponse().getContentAsString()).get("id").asText());
    }
    @Test void suggestionsPersistSeparatelyCorrectionsSurviveLoginAndReviewedValuesCannotBeOverwritten() throws Exception {
        when(images.extract(any(),eq("image/png"))).thenReturn(suggestion());var a=account();var id=upload(a);var r=receipts.findById(id).orElseThrow();
        assertThat(r.getDescription()).isNull();assertThat(r.getSuggestedDescription()).isEqualTo("Coffee and breakfast");assertThat(r.isTaxRelevant()).isFalse();
        assertThat(r.getMerchant()).isNull();assertThat(r.getAmount()).isNull();assertThat(r.getSuggestedMerchant()).isEqualTo("Oak Cafe");assertThat(r.getSuggestedAmount()).isEqualByComparingTo("6.72");assertThat(r.getExtractionModel()).isEqualTo("fixture-model");assertThat(r.getExtractionTimestamp()).isNotNull();assertThat(r.getExtractionDetails()).isNull();
        verify(images).extract(eq(image()),eq("image/png"));
        mvc.perform(put("/api/receipts/"+id).session(a).with(csrf()).contentType("application/json").content("{\"merchant\":\"Corrected cafe\",\"purchaseDate\":\"2025-09-19\",\"amount\":\"7.50\",\"currency\":\"USD\",\"category\":\"OFFICE\",\"taxRelevant\":true,\"description\":\"User confirmed breakfast\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.reviewStatus").value("REVIEWED"));
        clearInvocations(images);mvc.perform(post("/api/receipts/"+id+"/extract").session(a).with(csrf())).andExpect(status().isConflict());verifyNoInteractions(images);
        var email=users.findById(r.getUserId()).orElseThrow().getEmail();mvc.perform(post("/api/auth/logout").session(a).with(csrf())).andExpect(status().isNoContent());
        var login=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("email",email,"password","Fixture-only-password")))).andExpect(status().isOk()).andReturn();var session=(MockHttpSession)login.getRequest().getSession(false);
        mvc.perform(get("/api/receipts/"+id).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.merchant").value("Corrected cafe")).andExpect(jsonPath("$.amount").value("7.50")).andExpect(jsonPath("$.suggestedMerchant").value("Oak Cafe")).andExpect(jsonPath("$.extractionModel").value("fixture-model")).andExpect(jsonPath("$.description").value("User confirmed breakfast")).andExpect(jsonPath("$.suggestedDescription").value("Coffee and breakfast")).andExpect(jsonPath("$.taxRelevant").value(true));
    }
    @Test void crossAccountAndCsrfBlockImageAccessAndDeletionCleansOriginal() throws Exception {
        when(images.extract(any(),anyString())).thenReturn(suggestion());var a=account();var b=account();var id=upload(a);clearInvocations(images);
        for(String suffix:List.of("","/content","/extraction"))mvc.perform(get("/api/receipts/"+id+suffix).session(b)).andExpect(status().isNotFound());
        mvc.perform(post("/api/receipts/"+id+"/extract").session(b).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/receipts/"+id+"/extract").session(a)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/receipts/"+id).session(b).with(csrf())).andExpect(status().isNotFound());verifyNoInteractions(images);
        var path=storage.resolve(receipts.findById(id).orElseThrow().getStoredFileName());mvc.perform(delete("/api/receipts/"+id).session(a).with(csrf())).andExpect(status().isNoContent());assertThat(Files.exists(path)).isFalse();assertThat(receipts.existsById(id)).isFalse();
    }
    @Test void failedProviderStillSavesOriginalAndSafeFailureMetadata() throws Exception {
        when(images.extract(any(),anyString())).thenReturn(new ReceiptExtractionDetails(null,null,null,null,null,null,false,"OPENAI_IMAGE","Review manually","EXTRACTION_UNAVAILABLE","OPENAI_IMAGE",null,"We couldn't automatically read this receipt. Please enter the details manually.","fixture-model"));
        var a=account();var id=upload(a);var r=receipts.findById(id).orElseThrow();assertThat(r.getExtractionStatus()).isEqualTo("EXTRACTION_UNAVAILABLE");assertThat(r.getSuggestedAmount()).isNull();assertThat(Files.readAllBytes(storage.resolve(r.getStoredFileName()))).isEqualTo(image());
    }
    @Test void readableAndScannedPdfsNeverReachImageProvider() throws Exception {
        var a=account();for(String text:List.of("Merchant: Cedar Market\nDate: 2025-09-20\nTotal: USD 6.72", " "))mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file","test.pdf","application/pdf",PdfFixtures.pdf(text))).session(a).with(csrf())).andExpect(status().isCreated()).andExpect(jsonPath("$.extractionMethod").value("PDF_TEXT"));verifyNoInteractions(images);
    }
    @Test void additiveModelMigrationPreservesAllPreviousReceiptFields() throws Exception {
        String schema="m16_migration_fixture";String url=postgres.getJdbcUrl("postgres","postgres");
        org.flywaydb.core.Flyway.configure().dataSource(url,"postgres","postgres").schemas(schema).target("10").load().migrate();
        var owner=UUID.randomUUID();var id=UUID.randomUUID();
        jdbc.update("insert into "+schema+".users(id,email,password_hash,created_at,updated_at) values(?,?,?,now(),now())",owner,"migration@example.test","fixture");
        jdbc.update("insert into "+schema+".receipts(id,user_id,merchant,purchase_date,amount,currency,original_file_name,stored_file_name,content_type,file_size,tax_relevant,status,review_status,suggested_merchant,suggested_amount,extraction_status,extraction_method,extraction_timestamp,created_at,updated_at) values(?,?,?,'2025-01-01',12.34,'USD','old.pdf','old.pdf','application/pdf',10,true,'SAVED','REVIEWED','Earlier suggestion',11.11,'PARTIAL','PDF_TEXT',now(),now(),now())",id,owner,"Confirmed merchant");
        var before=jdbc.queryForObject("select to_jsonb(r)::text from "+schema+".receipts r where id=?",String.class,id);
        org.flywaydb.core.Flyway.configure().dataSource(url,"postgres","postgres").schemas(schema).target("11").load().migrate();
        assertThat(jdbc.queryForObject("select (to_jsonb(r)-'extraction_model')::text from "+schema+".receipts r where id=?",String.class,id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select extraction_model from "+schema+".receipts where id=?",String.class,id)).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"png","jpg"})
    void duplicateUploadAndRefreshExtractExactlyOnce(String format) throws Exception {
        when(images.extract(any(),anyString())).thenReturn(suggestion());var a=account();var token=UUID.randomUUID();
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(3,3,java.awt.image.BufferedImage.TYPE_INT_RGB),format,out);
        String type=format.equals("png")?"image/png":"image/jpeg";UUID id=null;
        for(int i=0;i<2;i++){
            var result=mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file","capture."+format,type,out.toByteArray())).header("Idempotency-Key",token).session(a).with(csrf())).andExpect(status().isCreated()).andReturn();
            var returned=UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());if(id==null)id=returned;else assertThat(returned).isEqualTo(id);
        }
        for(int i=0;i<3;i++)mvc.perform(get("/api/receipts/"+id).session(a)).andExpect(status().isOk()).andExpect(jsonPath("$.reviewStatus").value("NEEDS_REVIEW"));
        workflow.extractAutomatically(receipts.findById(id).orElseThrow().getUserId(),id);
        verify(images,times(1)).extract(any(),eq(type));
        assertThat(jdbc.queryForObject("select count(*) from receipts where upload_request_id=?",Integer.class,token)).isEqualTo(1);
    }
    @Test void uploadTokensAreAccountScopedAndFailedExtractionStillDeduplicates() throws Exception {
        when(images.extract(any(),anyString())).thenReturn(ReceiptExtractionDetails.unavailable("EXTRACTION_UNAVAILABLE","OPENAI_IMAGE","Please enter the details manually."));
        var a=account();var b=account();var token=UUID.randomUUID();var ids=new HashSet<String>();
        for(var session:List.of(a,a,b)){
            var result=mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file","capture.png","image/png",image())).header("Idempotency-Key",token).session(session).with(csrf())).andExpect(status().isCreated()).andExpect(jsonPath("$.extractionStatus").value("EXTRACTION_UNAVAILABLE")).andReturn();
            var id=json.readTree(result.getResponse().getContentAsString()).get("id").asText();ids.add(id);assertThat(Files.exists(storage.resolve(receipts.findById(UUID.fromString(id)).orElseThrow().getStoredFileName()))).isTrue();
        }
        assertThat(ids).hasSize(2);verify(images,times(2)).extract(any(),eq("image/png"));
    }
    @Test void explicitRetryVersionPreventsRepeatedRequestsAndRapidNewAttempts() throws Exception {
        when(images.extract(any(),anyString())).thenReturn(suggestion());var a=account();var id=upload(a);var original=receipts.findById(id).orElseThrow().getUpdatedAt().toString();clearInvocations(images);
        mvc.perform(post("/api/receipts/"+id+"/extract").header("If-Match",original).session(a).with(csrf())).andExpect(status().isTooManyRequests());verifyNoInteractions(images);
        jdbc.update("update receipts set extraction_timestamp=now()-interval '1 minute' where id=?",id);
        for(int i=0;i<2;i++)mvc.perform(post("/api/receipts/"+id+"/extract").header("If-Match",original).session(a).with(csrf())).andExpect(status().isOk());
        verify(images,times(1)).extract(any(),eq("image/png"));
        var next=receipts.findById(id).orElseThrow().getUpdatedAt().toString();mvc.perform(post("/api/receipts/"+id+"/extract").header("If-Match",next).session(a).with(csrf())).andExpect(status().isTooManyRequests());
        verify(images,times(1)).extract(any(),eq("image/png"));
    }
    @Test void uploadRequestMigrationDoesNotChangeHistoricalFields() throws Exception {
        String schema="m17_migration_fixture";String url=postgres.getJdbcUrl("postgres","postgres");
        org.flywaydb.core.Flyway.configure().dataSource(url,"postgres","postgres").schemas(schema).target("11").load().migrate();
        var owner=UUID.randomUUID();var id=UUID.randomUUID();jdbc.update("insert into "+schema+".users(id,email,password_hash,created_at,updated_at) values(?,?,?,now(),now())",owner,"migration@example.test","fixture");
        jdbc.update("insert into "+schema+".receipts(id,user_id,merchant,purchase_date,amount,currency,original_file_name,stored_file_name,content_type,file_size,tax_relevant,status,review_status,created_at,updated_at) values(?,?,?,'2025-01-01',12.34,'USD','old.pdf','old.pdf','application/pdf',10,true,'SAVED','REVIEWED',now(),now())",id,owner,"Confirmed merchant");
        var before=jdbc.queryForObject("select to_jsonb(r)::text from "+schema+".receipts r where id=?",String.class,id);
        org.flywaydb.core.Flyway.configure().dataSource(url,"postgres","postgres").schemas(schema).load().migrate();
        assertThat(jdbc.queryForObject("select (to_jsonb(r)-'upload_request_id'-'suggested_description')::text from "+schema+".receipts r where id=?",String.class,id)).isEqualTo(before);
    }

    @Test void concurrentDuplicateUploadDuringExtractionReusesStoredReceipt() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(images.extract(any(),anyString())).thenAnswer(invocation->{entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Fixture timed out");return suggestion();});
        var a=account();var token=UUID.randomUUID();var bytes=image();
        java.util.concurrent.Callable<String> upload=()->mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file","capture.png","image/png",bytes)).header("Idempotency-Key",token).session(a).with(csrf())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var original=pool.submit(upload);assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try{var duplicate=pool.submit(upload).get(5,java.util.concurrent.TimeUnit.SECONDS);release.countDown();var first=original.get(5,java.util.concurrent.TimeUnit.SECONDS);assertThat(json.readTree(first).get("id")).isEqualTo(json.readTree(duplicate).get("id"));}
            finally{release.countDown();}
        }
        verify(images,times(1)).extract(any(),eq("image/png"));assertThat(jdbc.queryForObject("select count(*) from receipts where upload_request_id=?",Integer.class,token)).isEqualTo(1);
    }

    @Test void descriptionMigrationPreservesConfirmedValuesAndLeavesSuggestionsUnknown() throws Exception {
        String schema="m18_migration_fixture";String url=postgres.getJdbcUrl("postgres","postgres");
        org.flywaydb.core.Flyway.configure().dataSource(url,"postgres","postgres").schemas(schema).target("12").load().migrate();
        var owner=UUID.randomUUID();var id=UUID.randomUUID();jdbc.update("insert into "+schema+".users(id,email,password_hash,created_at,updated_at) values(?,?,?,now(),now())",owner,"migration@example.test","fixture");
        jdbc.update("insert into "+schema+".receipts(id,user_id,merchant,purchase_date,amount,currency,original_file_name,stored_file_name,content_type,file_size,tax_relevant,status,review_status,description,created_at,updated_at) values(?,?,?,'2025-01-01',12.34,'USD','old.pdf','old.pdf','application/pdf',10,true,'SAVED','REVIEWED','Existing confirmed description',now(),now())",id,owner,"Confirmed merchant");
        var before=jdbc.queryForObject("select to_jsonb(r)::text from "+schema+".receipts r where id=?",String.class,id);
        org.flywaydb.core.Flyway.configure().dataSource(url,"postgres","postgres").schemas(schema).load().migrate();
        assertThat(jdbc.queryForObject("select (to_jsonb(r)-'suggested_description')::text from "+schema+".receipts r where id=?",String.class,id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select suggested_description from "+schema+".receipts where id=?",String.class,id)).isNull();
    }

}
