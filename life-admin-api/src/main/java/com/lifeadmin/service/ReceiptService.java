package com.lifeadmin.service;
import com.lifeadmin.dto.*;
import com.lifeadmin.entity.*;
import com.lifeadmin.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.util.*;
import java.time.LocalDate;
@Service
public class ReceiptService {
    private final ReceiptRepository receipts;private final UserRepository users;private final DocumentStorage storage;private final ReceiptFileValidator validator;private final TransactionTemplate transactions;private final ReceiptExtractionWorkflow extraction;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ReceiptService.class);
    public ReceiptService(ReceiptRepository receipts,UserRepository users,DocumentStorage storage,ReceiptFileValidator validator,PlatformTransactionManager manager,ReceiptExtractionWorkflow extraction){this.receipts=receipts;this.users=users;this.storage=storage;this.validator=validator;transactions=new TransactionTemplate(manager);this.extraction=extraction;}
    private ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Receipt not found.");}
    public ReceiptResponse create(UUID owner,ReceiptInput input,MultipartFile file) throws IOException {
        return create(owner,input,file,null);
    }
    public ReceiptResponse create(UUID owner,ReceiptInput input,MultipartFile file,UUID requestId) throws IOException {
        var valid=validator.validate(file);String[] key={null};boolean[] created={false};
        ReceiptResponse saved;
        try{saved=transactions.execute(tx->{
            var user=users.lockById(owner).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please log in again."));
            if(user.isDeletionPending())throw new ResponseStatusException(HttpStatus.CONFLICT,"Account deletion is incomplete. Open Settings and retry Delete permanently.");
            if(requestId!=null){
                var existing=receipts.findByUserIdAndUploadRequestId(owner,requestId);
                if(existing.isPresent()){
                    if(!existing.get().getStatus().equals("SAVED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"This upload is pending deletion.");
                    return ReceiptResponse.from(existing.get());
                }
            }
            key[0]=storage.store(valid.content(),valid.extension());
            var receipt=new Receipt(owner,input,valid.originalName(),key[0],valid.contentType(),valid.content().length);
            receipt.identifyUpload(requestId);created[0]=true;
            return ReceiptResponse.from(receipts.saveAndFlush(receipt));
        });}catch(RuntimeException e){if(key[0]!=null)try{storage.delete(key[0]);}catch(RuntimeException cleanup){log.error("Receipt creation cleanup failed; operator reconciliation required");}throw e;}
        // Commit storage/metadata first. Extraction failures must never remove the upload.
        if(input==null && created[0]){
            try{extraction.extractAutomatically(owner,saved.id());}catch(RuntimeException e){log.warn("Receipt stored but automatic reading did not complete; manual review remains available");}
            return get(owner,saved.id());
        }
        return saved;
    }
    public ReceiptResponse get(UUID owner,UUID id){return ReceiptResponse.from(receipts.findByIdAndUserId(id,owner).orElseThrow(this::missing));}
    private Receipt active(UUID owner,UUID id){var r=receipts.lockOwned(id,owner).orElseThrow(this::missing);if(!r.getStatus().equals("SAVED"))throw missing();return r;}
    @Transactional public ReceiptResponse update(UUID owner,UUID id,ReceiptInput input){var r=active(owner,id);if(storage.inspect(r.getStoredFileName(),r.getFileSize(),r.getContentType())!=DocumentStorage.FileState.AVAILABLE)throw new ResponseStatusException(HttpStatus.CONFLICT,"The receipt file is unavailable or invalid. Check the original file before saving.");r.update(input);return ReceiptResponse.from(r);}
    public record FileContent(byte[] bytes,String type,String filename){}
    @Transactional public FileContent content(UUID owner,UUID id){var r=active(owner,id);String name=r.getOriginalFileName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}\\p{Cf}]","_");return new FileContent(storage.read(r.getStoredFileName()),r.getContentType(),name);}
    public void delete(UUID owner,UUID id){
        transactions.executeWithoutResult(tx->receipts.lockOwned(id,owner).orElseThrow(this::missing).markDeletionPending());
        try{transactions.executeWithoutResult(tx->{var r=receipts.lockOwned(id,owner).orElseThrow(this::missing);storage.delete(r.getStoredFileName());receipts.delete(r);receipts.flush();});}
        catch(RuntimeException e){log.error("Receipt cleanup incomplete; retry required");throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Receipt deletion is incomplete. Please retry Delete permanently.");}
    }
    public record ReceiptPage(List<ReceiptResponse> items,boolean hasMore){}
    public ReceiptPage list(UUID owner,String search,Integer year,ReceiptCategory category,String sort,int page){
        if(search.length()>200 || page<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Check the search and page number.");
        validateYear(year);
        Specification<Receipt> spec=(root,q,cb)->cb.equal(root.get("userId"),owner);
        if(!search.isBlank()){String pattern="%"+search.strip().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";spec=spec.and((root,q,cb)->cb.or(cb.like(cb.lower(root.get("merchant")),pattern,'!'),cb.like(cb.lower(root.get("description")),pattern,'!')));}
        if(year!=null)spec=spec.and((root,q,cb)->cb.or(cb.between(root.get("purchaseDate"),LocalDate.of(year,1,1),LocalDate.of(year,12,31)),cb.and(cb.isNull(root.get("purchaseDate")),cb.greaterThanOrEqualTo(root.get("createdAt"),LocalDate.of(year,1,1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),cb.lessThan(root.get("createdAt"),LocalDate.of(year+1,1,1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))));
        if(category!=null)spec=spec.and((root,q,cb)->cb.equal(root.get("category"),category));
        Sort order=switch(sort){case "newest"->Sort.by("purchaseDate").descending();case "oldest"->Sort.by("purchaseDate").ascending();case "amount-high"->Sort.by("amount").descending();case "amount-low"->Sort.by("amount").ascending();default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a supported receipt sort order.");};
        var result=receipts.findAll(spec,PageRequest.of(page,25,order.and(Sort.by("id"))));return new ReceiptPage(result.map(ReceiptResponse::from).getContent(),result.hasNext());
    }
    public static void validateYear(Integer year){if(year!=null && (year<1900 || year>9998))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a year between 1900 and 9998.");}
}
