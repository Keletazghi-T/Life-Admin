package com.lifeadmin;
import com.lifeadmin.service.*;
import com.lifeadmin.entity.ReceiptCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
class ReceiptTextExtractionTest {
 private final LocalReceiptExtractionService service=new LocalReceiptExtractionService(new PdfTextExtractor(),new ReceiptTextParser());
 private final ReceiptTextParser parser=new ReceiptTextParser();
 @Test void extractsActualTextFromPdfAndKeepsDecimalPrecision()throws Exception{
  var r=service.extract(PdfFixtures.pdf("Cedar Market\nDate:2025-09-20\nSubtotal: USD 5.00\nTax: USD 0.72\nTotal:USD 6.72\nCategory: MEALS"),"application/pdf");
  assertThat(r.merchant()).isEqualTo("Cedar Market");assertThat(r.purchaseDate()).hasToString("2025-09-20");assertThat(r.amount()).isEqualByComparingTo("6.72");assertThat(r.currency()).isEqualTo("USD");assertThat(r.category()).isEqualTo(ReceiptCategory.MEALS);assertThat(r.extractionStatus()).isEqualTo("EXTRACTED");assertThat(r.source()).isEqualTo("PDF_TEXT");assertThat(r.taxRelevant()).isFalse();
 }
 @Test void missingMerchantIsBlankRatherThanReceiptHeadingOrDate(){var r=parser.parse("RECEIPT\nDate: 2025-09-20\nTotal: USD 6.72");assertThat(r.merchant()).isNull();assertThat(r.purchaseDate()).isNotNull();assertThat(r.amount()).isNotNull();assertThat(r.extractionError()).contains("merchant");}
 @Test void missingDateIsNeverUploadDate(){assertThat(parser.parse("Merchant: Cedar Market\nTotal: USD 6.72").purchaseDate()).isNull();}
 @Test void missingTotalDoesNotUseSubtotalTaxCashOrChange(){assertThat(parser.parse("Merchant: Cedar Market\nSubtotal: USD 6.00\nTax: USD 0.72\nCash: USD 20.00\nChange: USD 13.28").amount()).isNull();}
 @ParameterizedTest @ValueSource(strings={"03/04/2025","2025-02-30","02/29/2025","99/99/2025","September 31, 2025","2999-01-01","1899-01-01","2025-01-01 maybe","09/20/25"})
 void ambiguousInvalidFutureAndUnsupportedDatesStayBlank(String date){assertThat(parser.parse("Date: "+date).purchaseDate()).isNull();}
 @ParameterizedTest @ValueSource(strings={"2025-09-20","09/20/2025","20/09/2025","September 20, 2025","20 Sep 2025","2025-09-20 14:35"})
 void unambiguousDatesAreReadWithoutLocaleGuesses(String date){assertThat(parser.parse("Date: "+date).purchaseDate()).hasToString("2025-09-20");}
 @ParameterizedTest @ValueSource(strings={"6.7.2","6,72","1,23.45","-6.72","(6.72)","0.00","6.7O","6.720","1000000000000.00","6.72 or 7.00","NaN","6.72 refunded"})
 void malformedOrUncertainTotalsStayBlank(String value){assertThat(parser.parse("Total: USD "+value).amount()).isNull();}
 @Test void conflictingCandidatesStayBlankAndGrandTotalHasPriority(){
  assertThat(parser.parse("Total: USD 6.72\nTotal: USD 7.50").amount()).isNull();assertThat(parser.parse("Date: 2025-01-01\nDate: 2025-02-02").purchaseDate()).isNull();assertThat(parser.parse("Merchant: Cedar Market\nMerchant: Other Seller").merchant()).isNull();
  assertThat(parser.parse("Subtotal: USD 5.00\nTotal: USD 6.72\nGrand Total: USD 7.50\nCash: USD 20.00").amount()).isEqualByComparingTo("7.50");assertThat(parser.parse("Total: USD 1,234.56").amount()).isEqualByComparingTo("1234.56");
 }
 @Test void dollarSignAloneDoesNotInventUsdAndCategoryNeedsExplicitEvidence(){
  var r=parser.parse("Merchant: Cedar Market\nTotal: $6.72");assertThat(r.amount()).isEqualByComparingTo("6.72");assertThat(r.currency()).isNull();assertThat(r.category()).isNull();assertThat(parser.parse("Total: CAD 6.72").currency()).isEqualTo("CAD");assertThat(parser.parse("USD 6.72\nCAD 8.00").currency()).isNull();
 }
 @Test void imagesAndScannedPdfNeedOcrAndHaveNoFabricatedFields()throws Exception{
  for(String type:new String[]{"image/jpeg","image/png","application/pdf"}){var r=service.extract(type.equals("application/pdf")?PdfFixtures.pdf(""):new byte[]{1},type);assertThat(r.extractionStatus()).isEqualTo("OCR_REQUIRED");assertThat(r.merchant()).isNull();assertThat(r.amount()).isNull();assertThat(r.purchaseDate()).isNull();assertThat(r.currency()).isNull();assertThat(r.category()).isNull();assertThat(r.extractionError()).contains("not configured");}
 }
 @Test void unreadablePdfAndPageLimitReturnSafeUnavailableResult()throws Exception{
  for(byte[] bytes:new byte[][]{"%PDF-broken".getBytes(),PdfFixtures.pdf("Receipt",101,false)}){var r=service.extract(bytes,"application/pdf");assertThat(r.extractionStatus()).isEqualTo("EXTRACTION_UNAVAILABLE");assertThat(r.merchant()).isNull();assertThat(r.extractionError()).doesNotContain("Exception","/tmp/");}
 }
}
