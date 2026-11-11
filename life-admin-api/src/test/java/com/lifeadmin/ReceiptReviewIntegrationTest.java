package com.lifeadmin;
import com.lifeadmin.repository.*;
import com.lifeadmin.service.*;
import com.lifeadmin.ai.DocumentAnalysisProvider;
import com.lifeadmin.entity.ReceiptReviewStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.profiles.active=dev","app.ai.openai.api-key="}) @AutoConfigureMockMvc
class ReceiptReviewIntegrationTest extends AccountTestSupport {
 static final EmbeddedPostgres postgres;static final Path storage;
 static {try{postgres=EmbeddedPostgres.builder().start();storage=Files.createTempDirectory("life-admin-review-test-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");r.add("app.storage-directory",storage::toString);}
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ReceiptRepository receipts;@Autowired JdbcTemplate jdbc;
 @MockitoSpyBean LocalDocumentStorage files;@MockitoSpyBean LocalReceiptExtractionService extractor;@MockitoBean DocumentAnalysisProvider ai;
 @AfterEach void noAi(){verifyNoInteractions(ai);}
 @AfterAll static void close() throws Exception {postgres.close();try(var paths=Files.walk(storage)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
 private UUID upload(MockHttpSession a) throws Exception {return UUID.fromString(json.readTree(mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file","receipt.pdf","application/pdf",PdfFixtures.pdf("Merchant: Cedar Market\nDate: 2025-09-20\nTotal: USD 6.72\nCategory: MEALS"))).session(a).with(csrf())).andExpect(status().isCreated()).andExpect(jsonPath("$.reviewStatus").value("NEEDS_REVIEW")).andExpect(jsonPath("$.merchant").doesNotExist()).andExpect(jsonPath("$.amount").doesNotExist()).andReturn().getResponse().getContentAsString()).get("id").asText());}
 private ResultActions extract(MockHttpSession a,UUID id) throws Exception{return mvc.perform(post("/api/receipts/"+id+"/extract").session(a).with(csrf()));}
 private Map<String,Object> details(){return new HashMap<>(Map.of("merchant","Corrected merchant","purchaseDate","2025-02-01","amount","10.23","currency","USD","category","OFFICE","description","Corrected purchase","taxRelevant",true,"notes","User notes"));}
 private ResultActions save(MockHttpSession a,UUID id,Map<String,Object> fields) throws Exception {return mvc.perform(put("/api/receipts/"+id).session(a).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(fields)));}
 private JsonNode read(MockHttpSession a,String path) throws Exception {return json.readTree(mvc.perform(get(path).session(a)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
 @Test void uploadExtractionReviewAndReloadPersistConfirmedValuesAndProvenanceWithoutChangingFile() throws Exception {
  var a=account();var id=upload(a);var original=receipts.findById(id).orElseThrow();var bytes=Files.readAllBytes(storage.resolve(original.getStoredFileName()));
  mvc.perform(get("/api/receipts/extraction-capabilities").session(a)).andExpect(jsonPath("$.extractionEnabled").value(true));
  extract(a,id).andExpect(status().isOk()).andExpect(jsonPath("$.merchant").value("Cedar Market")).andExpect(jsonPath("$.amount").value("6.72")).andExpect(jsonPath("$.source").value("PDF_TEXT")).andExpect(jsonPath("$.notice").value("Automatically suggested details. Check every value against the original."));
  var draft=receipts.findById(id).orElseThrow();assertThat(draft.getReviewStatus()).isEqualTo(ReceiptReviewStatus.NEEDS_REVIEW);assertThat(draft.getMerchant()).isNull();assertThat(draft.getAmount()).isNull();assertThat(draft.getExtractedAt()).isNotNull();
  assertThat(read(a,"/api/receipts/"+id+"/extraction").get("merchant").asText()).isEqualTo("Cedar Market");
  save(a,id,details()).andExpect(status().isOk()).andExpect(jsonPath("$.reviewStatus").value("REVIEWED")).andExpect(jsonPath("$.amount").value("10.23")).andExpect(jsonPath("$.extractionSource").value("PDF_TEXT"));
  var saved=read(a,"/api/receipts/"+id);assertThat(saved.get("merchant").asText()).isEqualTo("Corrected merchant");assertThat(saved.get("purchaseDate").asText()).isEqualTo("2025-02-01");assertThat(saved.get("category").asText()).isEqualTo("OFFICE");assertThat(saved.get("description").asText()).isEqualTo("Corrected purchase");assertThat(saved.get("taxRelevant").asBoolean()).isTrue();assertThat(saved.get("notes").asText()).isEqualTo("User notes");
  extract(a,id).andExpect(status().isConflict());assertThat(Files.readAllBytes(storage.resolve(original.getStoredFileName()))).isEqualTo(bytes);
 }
 @Test void draftReviewReasonsAndConfirmedEditsUpdateYearCategoryTotalsAndCsv() throws Exception {
  var a=account();var id=upload(a);extract(a,id).andExpect(status().isOk());int year=LocalDate.now(ZoneOffset.UTC).getYear();
  var overview=read(a,"/api/receipts/tax?year="+year);assertThat(overview.get("summary").get("receiptCount").asInt()).isZero();assertThat(overview.get("recordsNeedingReview").get(0).get("reasons").toString()).contains("need your review","Missing merchant","Missing purchase date","Missing amount");
  for(String sort:List.of("date-asc","date-desc","merchant","category","amount-asc","amount-desc"))assertThat(read(a,"/api/receipts/tax/records?year="+year+"&filter=needs-review&sort="+sort).get("totalCount").asInt()).isEqualTo(1);
  var csv=mvc.perform(get("/api/receipts/tax/export?year="+year).session(a)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(csv.lines().count()).isEqualTo(1);assertThat(csv).doesNotContain("Cedar Market");
  save(a,id,details()).andExpect(status().isOk());overview=read(a,"/api/receipts/tax?year=2025");assertThat(overview.get("summary").get("totalAmount").asText()).isEqualTo("10.23");assertThat(overview.get("summary").get("categories").get(0).get("category").asText()).isEqualTo("OFFICE");assertThat(overview.get("recordsNeedingReview")).isEmpty();
  var fields=details();fields.put("purchaseDate","2024-01-01");fields.put("amount","7.89");fields.put("category","MEDICAL");save(a,id,fields).andExpect(status().isOk());assertThat(read(a,"/api/receipts/tax?year=2025").get("summary").get("receiptCount").asInt()).isZero();
  var summary=read(a,"/api/receipts/tax?year=2024").get("summary");assertThat(summary.get("totalAmount").asText()).isEqualTo("7.89");assertThat(summary.get("categories").get(0).get("amount").asText()).isEqualTo("7.89");assertThat(summary.get("categories").get(0).get("category").asText()).isEqualTo("MEDICAL");
  for(String scope:List.of("all","tax-relevant"))mvc.perform(get("/api/receipts/tax/export?year=2024&scope="+scope).session(a)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"2024-01-01\",\"Corrected merchant\",\"7.89\",\"USD\",\"Medical\""))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Cedar Market"))));
 }
 @Test void authenticatedCsrfAndBidirectionalIsolationApplyBeforeAnyFileAccess() throws Exception {
  var a=account();var b=account();var idA=upload(a);var idB=upload(b);clearInvocations(files);
  for(var pair:List.of(Map.entry(a,idB),Map.entry(b,idA))){extract(pair.getKey(),pair.getValue()).andExpect(status().isNotFound());save(pair.getKey(),pair.getValue(),details()).andExpect(status().isNotFound());for(String suffix:List.of("","/content","/extraction"))mvc.perform(get("/api/receipts/"+pair.getValue()+suffix).session(pair.getKey())).andExpect(status().isNotFound());}
  mvc.perform(post("/api/receipts/"+idA+"/extract").session(a)).andExpect(status().isForbidden());mvc.perform(post("/api/receipts/"+idA+"/extract").with(csrf())).andExpect(status().isUnauthorized());mvc.perform(get("/api/receipts/"+idA+"/extraction")).andExpect(status().isUnauthorized());
  verify(files,never()).read(anyString());verify(files,never()).inspect(anyString(),anyLong(),anyString());
 }
 @Test void invalidMissingAndPendingIdsFailWithoutExtraction() throws Exception {
  var a=account();extract(a,UUID.randomUUID()).andExpect(status().isNotFound());mvc.perform(post("/api/receipts/no-id/extract").session(a).with(csrf())).andExpect(status().isBadRequest());mvc.perform(get("/api/receipts/no-id/extraction").session(a)).andExpect(status().isBadRequest());
  var id=upload(a);mvc.perform(get("/api/receipts/"+id+"/extraction").session(a)).andExpect(status().isOk());jdbc.update("update receipts set status='DELETION_PENDING' where id=?",id);extract(a,id).andExpect(status().isNotFound());save(a,id,details()).andExpect(status().isNotFound());
 }
 @Test void invalidInputsCannotMarkReceiptReviewedOrChangeSuggestions() throws Exception {
  var a=account();var id=upload(a);extract(a,id).andExpect(status().isOk());
  for(var invalid:List.of(Map.entry("merchant","  "),Map.entry("purchaseDate","2026-02-30"),Map.entry("purchaseDate","1899-01-01"),Map.entry("purchaseDate","2999-01-01"),Map.entry("amount","0"),Map.entry("amount","-1"),Map.entry("amount","1.001"),Map.entry("amount","1000000000000"),Map.entry("currency","EUR"),Map.entry("currency",""),Map.entry("category","MADE_UP"))){var fields=details();fields.put(invalid.getKey(),invalid.getValue());save(a,id,fields).andExpect(status().isBadRequest());}
  for(String required:List.of("merchant","purchaseDate","amount")){var fields=details();fields.remove(required);save(a,id,fields).andExpect(status().isBadRequest());}
  var r=receipts.findById(id).orElseThrow();assertThat(r.getReviewStatus()).isEqualTo(ReceiptReviewStatus.NEEDS_REVIEW);assertThat(r.getMerchant()).isNull();assertThat(r.getSuggestedMerchant()).isEqualTo("Cedar Market");
  var fields=details();fields.put("userId",UUID.randomUUID().toString());fields.put("storedFileName","../another-user.pdf");fields.put("reviewStatus","NEEDS_REVIEW");save(a,id,fields).andExpect(status().isOk()).andExpect(jsonPath("$.reviewStatus").value("REVIEWED"));assertThat(receipts.findById(id).orElseThrow().getStoredFileName()).isEqualTo(r.getStoredFileName());
 }
 @Test void missingAndInvalidOwnedFilesBlockExtractionAndReview() throws Exception {
  var a=account();var id=upload(a);var r=receipts.findById(id).orElseThrow();var path=storage.resolve(r.getStoredFileName());byte[] original=Files.readAllBytes(path);var broken=original.clone();broken[0]='X';Files.write(path,broken);
  extract(a,id).andExpect(status().isConflict());save(a,id,details()).andExpect(status().isConflict());Files.delete(path);extract(a,id).andExpect(status().isConflict());save(a,id,details()).andExpect(status().isConflict());assertThat(receipts.findById(id).orElseThrow().getReviewStatus()).isEqualTo(ReceiptReviewStatus.NEEDS_REVIEW);
 }
 @Test void manualReviewWorksWithoutRerunningExtractionAndDraftAccountDeletionCleansFiles() throws Exception {
  var a=account();var id=upload(a);save(a,id,details()).andExpect(status().isOk()).andExpect(jsonPath("$.extractionSource").value("PDF_TEXT"));var draft=upload(a);extract(a,draft).andExpect(status().isOk());var key=receipts.findById(draft).orElseThrow().getStoredFileName();
  mvc.perform(delete("/api/account").session(a).with(csrf()).contentType("application/json").content("{\"currentPassword\":\"Fixture-only-password\",\"confirmation\":\"DELETE\"}")).andExpect(status().isNoContent());assertThat(receipts.existsById(draft)).isFalse();assertThat(Files.exists(storage.resolve(key))).isFalse();assertThat(receipts.existsById(id)).isFalse();
 }
 @Test void migrationFromMilestone12PreservesExistingMetadataAndMarksItReviewed() throws Exception {
  String schema="legacy_review_fixture";var url=postgres.getJdbcUrl("postgres","postgres");
  org.flywaydb.core.Flyway.configure().dataSource(url,"postgres","postgres").schemas(schema).target("8").load().migrate();
  var owner=UUID.randomUUID();var id=UUID.randomUUID();jdbc.update("insert into "+schema+".users(id,email,password_hash,created_at,updated_at) values(?,?,?,now(),now())",owner,"legacy@example.test","fixture");
  jdbc.update("insert into "+schema+".receipts(id,user_id,merchant,purchase_date,amount,currency,original_file_name,stored_file_name,content_type,file_size,tax_relevant,status,created_at,updated_at) values(?,?,?,'2025-01-01',12.34,'USD','legacy.pdf','legacy-key.pdf','application/pdf',10,true,'SAVED',now(),now())",id,owner,"Existing merchant");
  var before=jdbc.queryForObject("select to_jsonb(r)::text from "+schema+".receipts r where id=?",String.class,id);
  org.flywaydb.core.Flyway.configure().dataSource(url,"postgres","postgres").schemas(schema).target("9").load().migrate();
  var after=jdbc.queryForObject("select (to_jsonb(r)-'review_status'-'extraction_source'-'extracted_at'-'extraction_details')::text from "+schema+".receipts r where id=?",String.class,id);
  assertThat(after).isEqualTo(before);assertThat(jdbc.queryForObject("select review_status from "+schema+".receipts where id=?",String.class,id)).isEqualTo("REVIEWED");
 }
 @Test void photoUploadPersistsOcrRequiredWithoutInventingSuggestions() throws Exception {
  var a=account();var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);
  var result=mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file","photo.png","image/png",out.toByteArray())).session(a).with(csrf())).andExpect(status().isCreated()).andExpect(jsonPath("$.reviewStatus").value("NEEDS_REVIEW")).andExpect(jsonPath("$.extractionStatus").value("OCR_REQUIRED")).andExpect(jsonPath("$.suggestedMerchant").doesNotExist()).andExpect(jsonPath("$.suggestedAmount").doesNotExist()).andReturn();
  var id=UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());var r=receipts.findById(id).orElseThrow();assertThat(r.getExtractionTimestamp()).isNotNull();assertThat(r.getSuggestedCurrency()).isNull();assertThat(r.getExtractionError()).contains("photo");save(a,id,details()).andExpect(status().isOk());extract(a,id).andExpect(status().isConflict());assertThat(receipts.findById(id).orElseThrow().getMerchant()).isEqualTo("Corrected merchant");
 }
 @Test void legacySampleSuggestionsAreNeverPresentedAsRealAndReadingCannotChangeConfirmedValues() throws Exception {
  var a=account();var id=upload(a);
  jdbc.update("update receipts set extraction_status='NOT_ATTEMPTED',suggested_merchant=null,suggested_purchase_date=null,suggested_amount=null,suggested_currency=null,suggested_category=null,extraction_source='DEVELOPMENT_SAMPLE',extraction_details=? where id=?","{\"merchant\":\"Fake sample shop\",\"amount\":\"99.99\"}",id);
  var r=read(a,"/api/receipts/"+id+"/extraction");assertThat(r.get("merchant").isNull()).isTrue();assertThat(r.get("notice").asText()).contains("samples are not used");assertThat(r.toString()).doesNotContain("Fake sample shop","99.99");
  save(a,id,details()).andExpect(status().isOk());var before=receipts.findById(id).orElseThrow();extract(a,id).andExpect(status().isConflict());var after=receipts.findById(id).orElseThrow();assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());assertThat(after.getExtractionDetails()).isEqualTo(before.getExtractionDetails());
 }
 @Test void migrationFromV9RetainsAllExistingFieldsAndLeavesNewSuggestionsEmpty() throws Exception {
  String schema="legacy_extraction_fixture";var url=postgres.getJdbcUrl("postgres","postgres");org.flywaydb.core.Flyway.configure().dataSource(url,"postgres","postgres").schemas(schema).target("9").load().migrate();var owner=UUID.randomUUID();var id=UUID.randomUUID();
  jdbc.update("insert into "+schema+".users(id,email,password_hash,created_at,updated_at) values(?,?,?,now(),now())",owner,"legacy@example.test","fixture");jdbc.update("insert into "+schema+".receipts(id,user_id,merchant,purchase_date,amount,currency,original_file_name,stored_file_name,content_type,file_size,tax_relevant,status,review_status,extraction_source,extraction_details,created_at,updated_at) values(?,?,?,'2025-01-01',12.34,'USD','legacy.pdf','legacy.pdf','application/pdf',10,true,'SAVED','REVIEWED','DEVELOPMENT_SAMPLE','{}',now(),now())",id,owner,"Existing confirmed merchant");
  var before=jdbc.queryForObject("select to_jsonb(r)::text from "+schema+".receipts r where id=?",String.class,id);org.flywaydb.core.Flyway.configure().dataSource(url,"postgres","postgres").schemas(schema).load().migrate();var after=jdbc.queryForObject("select (to_jsonb(r)-'suggested_merchant'-'suggested_purchase_date'-'suggested_amount'-'suggested_currency'-'suggested_category'-'extraction_status'-'extraction_method'-'extraction_timestamp'-'extraction_error'-'extraction_model'-'upload_request_id'-'suggested_description')::text from "+schema+".receipts r where id=?",String.class,id);assertThat(after).isEqualTo(before);assertThat(jdbc.queryForObject("select extraction_status from "+schema+".receipts where id=?",String.class,id)).isEqualTo("NOT_ATTEMPTED");
 }

 @Test void providerFailureKeepsOriginalUploadAndMakesManualReviewAvailable() throws Exception {
  doThrow(new IllegalStateException("private internal error")).when(extractor).extract(any(byte[].class),eq("application/pdf"));var a=account();var id=upload(a);var r=receipts.findById(id).orElseThrow();assertThat(r.getExtractionStatus()).isEqualTo("EXTRACTION_UNAVAILABLE");assertThat(r.getExtractionError()).doesNotContain("private internal error");assertThat(Files.exists(storage.resolve(r.getStoredFileName()))).isTrue();save(a,id,details()).andExpect(status().isOk());
 }

}
