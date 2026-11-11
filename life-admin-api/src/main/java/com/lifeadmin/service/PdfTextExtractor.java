package com.lifeadmin.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.io.Writer;

@Component
public class PdfTextExtractor {
    static final int MAX_PAGES = 100;
    static final int MAX_CHARACTERS = 100_000;

    public String extract(byte[] pdf) {
        try (var document = Loader.loadPDF(pdf)) {
            if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent())
                throw invalid("This PDF is encrypted or restricts text extraction. Upload an unrestricted copy.");
            if (document.getNumberOfPages() > MAX_PAGES)
                throw invalid("This PDF exceeds the 100-page analysis limit. Upload a shorter document.");
            var output = new LimitedWriter();
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.writeText(document, output);
            String text = output.content.toString().strip();
            if (text.isBlank())
                throw invalid("No readable text was found. Scanned or image-only PDFs need OCR, which is not supported yet.");
            return text;
        } catch (InvalidPasswordException e) {
            throw invalid("This PDF is password-protected. Upload an unrestricted copy.");
        } catch (TextLimitException e) {
            throw invalid("This PDF exceeds the 100,000-character analysis limit. Upload a shorter document.");
        } catch (IOException e) {
            throw invalid("Text could not be extracted from this PDF. It may be damaged or unsupported.");
        }
    }
    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
    private static class TextLimitException extends IOException {}
    private static class LimitedWriter extends Writer {
        private final StringBuilder content = new StringBuilder();
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            if (content.length() + length > MAX_CHARACTERS) throw new TextLimitException();
            content.append(chars, offset, length);
        }
        @Override public void flush() {}
        @Override public void close() {}
    }
}
