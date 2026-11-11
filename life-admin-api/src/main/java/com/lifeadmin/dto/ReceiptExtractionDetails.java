package com.lifeadmin.dto;
import com.lifeadmin.entity.ReceiptCategory;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.*;
/** Nullable suggestions, never confirmed values. No confidence scores or inferred tax choice. */
public record ReceiptExtractionDetails(String merchant,LocalDate purchaseDate,
 @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount,String currency,
 ReceiptCategory category,String description,boolean taxRelevant,String source,String notice,
 String extractionStatus,String extractionMethod,Instant extractionTimestamp,String extractionError,String extractionModel) {
 public static ReceiptExtractionDetails result(String merchant,LocalDate date,BigDecimal amount,String currency,ReceiptCategory category,String status,String method,String reason){
  return new ReceiptExtractionDetails(merchant,date,amount,currency,category,null,false,method,"Automatically suggested details. Check every value against the original.",status,method,null,reason,null);
 }
 public static ReceiptExtractionDetails unavailable(String status,String method,String reason){return result(null,null,null,null,null,status,method,reason);}
}
