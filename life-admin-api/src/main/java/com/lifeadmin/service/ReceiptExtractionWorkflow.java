package com.lifeadmin.service;
import com.lifeadmin.dto.ReceiptExtractionDetails;
import com.lifeadmin.entity.*;
import com.lifeadmin.repository.ReceiptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.UUID;
@Service
public class ReceiptExtractionWorkflow {
 private final ReceiptRepository receipts;private final DocumentStorage storage;private final ReceiptExtractionService provider;
 public ReceiptExtractionWorkflow(ReceiptRepository receipts,DocumentStorage storage,ReceiptExtractionService provider){this.receipts=receipts;this.storage=storage;this.provider=provider;}
 public boolean available(){return true;}
 public boolean imageExtractionAvailable(){return provider.imageExtractionAvailable();}
 @Transactional public ReceiptExtractionDetails extract(UUID owner,UUID id){return extract(owner,id,null);}
 @Transactional public ReceiptExtractionDetails extract(UUID owner,UUID id,String expectedVersion){return perform(owner,id,expectedVersion,false);}
 @Transactional public ReceiptExtractionDetails extractAutomatically(UUID owner,UUID id){return perform(owner,id,null,true);}
 private ReceiptExtractionDetails perform(UUID owner,UUID id,String expectedVersion,boolean automatic){
  var r=receipts.lockOwned(id,owner).filter(receipt->receipt.getStatus().equals("SAVED")).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Receipt not found."));
  if(automatic && (r.getReviewStatus()==ReceiptReviewStatus.REVIEWED || !r.getExtractionStatus().equals("NOT_ATTEMPTED")))return response(r);
  if(r.getReviewStatus()==ReceiptReviewStatus.REVIEWED)throw new ResponseStatusException(HttpStatus.CONFLICT,"This receipt is already reviewed. Its confirmed details will not be overwritten.");
  if(expectedVersion!=null && !expectedVersion.equals(r.getUpdatedAt().toString()))return response(r);
  if(expectedVersion!=null && r.getExtractionTimestamp()!=null && r.getExtractionTimestamp().isAfter(java.time.Instant.now().minusSeconds(30)))
   throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Please wait 30 seconds after the last reading attempt before trying again.");
  if(storage.inspect(r.getStoredFileName(),r.getFileSize(),r.getContentType())!=DocumentStorage.FileState.AVAILABLE)throw new ResponseStatusException(HttpStatus.CONFLICT,"The receipt file is unavailable or invalid. Check the original file first.");
  ReceiptExtractionDetails result;
  try{result=provider.extract(storage.read(r.getStoredFileName()),r.getContentType());}
  catch(RuntimeException e){result=ReceiptExtractionDetails.unavailable("EXTRACTION_UNAVAILABLE","NONE","Automatic reading is unavailable. The original receipt is saved; enter the details manually.");}
  r.applyExtraction(result);return response(r);
 }
 @Transactional(readOnly=true) public ReceiptExtractionDetails suggestions(UUID owner,UUID id){
  var r=receipts.findByIdAndUserId(id,owner).filter(receipt->receipt.getStatus().equals("SAVED")).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Receipt not found."));return response(r);
 }
 private ReceiptExtractionDetails response(Receipt r){
  boolean attempted=!r.getExtractionStatus().equals("NOT_ATTEMPTED");
  return new ReceiptExtractionDetails(r.getSuggestedMerchant(),r.getSuggestedPurchaseDate(),r.getSuggestedAmount(),r.getSuggestedCurrency(),r.getSuggestedCategory(),r.getSuggestedDescription(),false,attempted?r.getExtractionMethod():"NONE",attempted?"Automatically suggested details. Check every value against the original.":"No real extraction has been attempted. Earlier development samples are not used as suggestions.",r.getExtractionStatus(),r.getExtractionMethod(),r.getExtractionTimestamp(),r.getExtractionError(),r.getExtractionModel());
 }
}
