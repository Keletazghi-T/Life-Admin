package com.lifeadmin;
import com.lifeadmin.service.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
class PdfTextExtractorTest {
    private final PdfTextExtractor extractor = new PdfTextExtractor();
    @Test void extractsTextFromEveryPage() throws Exception {
        assertThat(extractor.extract(PdfFixtures.pdf("Respond by October 15, 2026.", 2, false)))
                .contains("Respond by October 15, 2026.").containsSubsequence("2026.", "Respond");
    }
    @Test void rejectsMalformedPdf() {
        assertThatThrownBy(() -> extractor.extract("%PDF-1.7 broken".getBytes()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("damaged or unsupported");
    }
    @Test void rejectsImageOnlyOrBlankPdfWithoutInventingText() throws Exception {
        byte[] pdf = PdfFixtures.pdf("");
        assertThatThrownBy(() -> extractor.extract(pdf)).hasMessageContaining("No readable text").hasMessageContaining("OCR");
    }
    @Test void rejectsEncryptedPdf() throws Exception {
        byte[] pdf = PdfFixtures.pdf("Private letter", 1, true);
        assertThatThrownBy(() -> extractor.extract(pdf)).hasMessageContaining("password-protected");
    }
    @Test void rejectsExcessPagesInsteadOfSilentlyTruncating() throws Exception {
        byte[] pdf = PdfFixtures.pdf("", 101, false);
        assertThatThrownBy(() -> extractor.extract(pdf)).hasMessageContaining("100-page");
    }
    @Test void rejectsExcessTextInsteadOfSilentlyTruncating() throws Exception {
        byte[] pdf = PdfFixtures.pdf("a".repeat(100_001));
        assertThatThrownBy(() -> extractor.extract(pdf)).hasMessageContaining("100,000-character");
    }
}
