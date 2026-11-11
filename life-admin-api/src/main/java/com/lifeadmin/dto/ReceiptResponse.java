package com.lifeadmin.dto;
import com.lifeadmin.entity.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonFormat;
public record ReceiptResponse(UUID id,String merchant,LocalDate purchaseDate,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount,String currency,ReceiptCategory category,String description,String originalFileName,String contentType,long fileSize,boolean taxRelevant,String notes,String status,Instant createdAt,Instant updatedAt,ReceiptReviewStatus reviewStatus,String extractionSource,Instant extractedAt,String suggestedMerchant,LocalDate suggestedPurchaseDate,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal suggestedAmount,String suggestedCurrency,ReceiptCategory suggestedCategory,String extractionStatus,String extractionMethod,Instant extractionTimestamp,String extractionError,String extractionModel,String suggestedDescription) {
    public static ReceiptResponse from(Receipt r) { return new ReceiptResponse(r.getId(),r.getMerchant(),r.getPurchaseDate(),r.getAmount(),r.getCurrency(),r.getCategory(),r.getDescription(),r.getOriginalFileName(),r.getContentType(),r.getFileSize(),r.isTaxRelevant(),r.getNotes(),r.getStatus(),r.getCreatedAt(),r.getUpdatedAt(),r.getReviewStatus(),r.getExtractionSource(),r.getExtractedAt(),r.getSuggestedMerchant(),r.getSuggestedPurchaseDate(),r.getSuggestedAmount(),r.getSuggestedCurrency(),r.getSuggestedCategory(),r.getExtractionStatus(),r.getExtractionMethod(),r.getExtractionTimestamp(),r.getExtractionError(),r.getExtractionModel(),r.getSuggestedDescription()); }
}
