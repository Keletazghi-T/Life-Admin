package com.lifeadmin.service;

import com.lifeadmin.ai.OpenAiReceiptExtractionProvider;
import com.lifeadmin.dto.ReceiptExtractionDetails;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** File routing stays independent of receipt ownership, persistence and provider HTTP details. */
@Service @Primary
public class RoutingReceiptExtractionService implements ReceiptExtractionService {
    private final LocalReceiptExtractionService local;
    private final OpenAiReceiptExtractionProvider images;
    public RoutingReceiptExtractionService(LocalReceiptExtractionService local, OpenAiReceiptExtractionProvider images) {
        this.local = local; this.images = images;
    }
    @Override public boolean imageExtractionAvailable() { return images.configured(); }
    @Override public ReceiptExtractionDetails extract(byte[] content, String contentType) {
        return contentType.equals("image/jpeg") || contentType.equals("image/png")
                ? images.extract(content, contentType) : local.extract(content, contentType);
    }
}
