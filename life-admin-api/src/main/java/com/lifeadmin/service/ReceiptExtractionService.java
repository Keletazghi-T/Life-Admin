package com.lifeadmin.service;
import com.lifeadmin.dto.ReceiptExtractionDetails;
/** Provider boundary. Only the backend supplies an owned, validated receipt file. */
public interface ReceiptExtractionService {
 default boolean imageExtractionAvailable(){return false;}
 ReceiptExtractionDetails extract(byte[] content,String contentType);
}
