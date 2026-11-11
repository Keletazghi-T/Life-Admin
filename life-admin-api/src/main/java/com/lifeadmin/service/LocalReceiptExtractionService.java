package com.lifeadmin.service;
import com.lifeadmin.dto.ReceiptExtractionDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
@Service
public class LocalReceiptExtractionService implements ReceiptExtractionService {
 private final PdfTextExtractor pdf;private final ReceiptTextParser parser;
 public LocalReceiptExtractionService(PdfTextExtractor pdf,ReceiptTextParser parser){this.pdf=pdf;this.parser=parser;}
 @Override public ReceiptExtractionDetails extract(byte[] content,String contentType){
  if(contentType.equals("image/jpeg") || contentType.equals("image/png"))return ReceiptExtractionDetails.unavailable("OCR_REQUIRED","NONE","This receipt is a photo. Automatic photo reading is not configured. Enter the details from the original.");
  if(!contentType.equals("application/pdf"))return ReceiptExtractionDetails.unavailable("EXTRACTION_UNAVAILABLE","NONE","This file type cannot be read automatically.");
  try{return parser.parse(pdf.extract(content));}
  catch(ResponseStatusException e){boolean scanned=e.getReason()!=null && e.getReason().startsWith("No readable text");return ReceiptExtractionDetails.unavailable(scanned?"OCR_REQUIRED":"EXTRACTION_UNAVAILABLE","PDF_TEXT",scanned?"No readable text was found in this PDF. It may be scanned. Automatic image reading is not configured; enter the details manually.":"The PDF could not be read safely or exceeds the text extraction limits. Enter the details from the original.");}
 }
}
