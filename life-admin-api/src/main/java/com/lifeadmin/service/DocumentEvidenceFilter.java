package com.lifeadmin.service;

import com.lifeadmin.dto.AnalysisContent;

/** Literal source grounding only. Call after validating the complete, unfiltered analysis. */
public final class DocumentEvidenceFilter {
    private DocumentEvidenceFilter() {}

    public static AnalysisContent filter(AnalysisContent result, String source) {
        String normalized = normalize(source);
        return new AnalysisContent(result.documentType(), result.summary(), result.actionRequired(),
                result.deadline(), result.requiredDocuments(), result.nextSteps(), result.warnings(),
                result.meaning(), result.evidence().stream().filter(q -> matches(normalized, q)).toList(),
                result.importantDates().stream().filter(d -> matches(normalized, d.evidence())).toList());
    }

    private static boolean matches(String normalizedSource, String excerpt) {
        String normalizedExcerpt = normalize(excerpt);
        return !normalizedExcerpt.isEmpty() && normalizedSource.contains(normalizedExcerpt);
    }

    private static String normalize(String text) {
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (int point : text.codePoints().toArray()) {
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = !out.isEmpty();
                continue;
            }
            if (space) out.append(' ');
            space = false;
            // Do not remove punctuation, hyphenated line breaks, accents, or change letter case.
            // In particular, en/em dashes and mathematical minus signs remain distinct.
            out.appendCodePoint(switch (point) {
                case 0x2018, 0x2019 -> '\'';
                case 0x201C, 0x201D -> '"';
                case 0x2010, 0x2011 -> '-';
                default -> point;
            });
        }
        return out.toString();
    }
}
