package com.lifeadmin;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import java.io.*;
public final class PdfFixtures {
    private PdfFixtures() {}
    public static byte[] pdf(String text) throws IOException { return pdf(text, 1, false); }
    public static byte[] pdf(String text, int pages, boolean encrypted) throws IOException {
        try (var pdf = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                var page = new PDPage(); pdf.addPage(page);
                if (!text.isEmpty()) try (var stream = new PDPageContentStream(pdf, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    for(String line:text.split("\\R")){stream.showText(line);stream.newLineAtOffset(0,-18);}
                    stream.endText();
                }
            }
            if (encrypted) pdf.protect(new StandardProtectionPolicy("owner-password", "reader-password", new AccessPermission()));
            pdf.save(output);
            return output.toByteArray();
        }
    }
}
