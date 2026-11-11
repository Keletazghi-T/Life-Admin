package com.lifeadmin.entity;
import com.lifeadmin.dto.ReceiptInput;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
@Entity @Table(name="receipts") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Receipt {
    @Id private UUID id;
    private UUID uploadRequestId;
    public void identifyUpload(UUID requestId) { uploadRequestId=requestId; }
    @Column(nullable=false) private UUID userId;
    @Column(length=200) private String merchant;
    private LocalDate purchaseDate;
    @Column(precision=14,scale=2) private BigDecimal amount;
    @Column(nullable=false,length=3) private String currency;
    @Enumerated(EnumType.STRING) @Column(length=30) private ReceiptCategory category;
    @Column(length=2000) private String description;
    @Column(nullable=false,length=255) private String originalFileName;
    @Column(nullable=false,length=255) private String storedFileName;
    @Column(nullable=false,length=100) private String contentType;
    @Column(nullable=false) private long fileSize;
    @Column(nullable=false) private boolean taxRelevant;
    @Column(length=4000) private String notes;
    @Column(nullable=false,length=30) private String status;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private ReceiptReviewStatus reviewStatus;
    @Column(length=40) private String extractionSource;
    private Instant extractedAt;
    @Column(columnDefinition="text") private String extractionDetails;
    public void recordExtraction(String source,String details) {extractionSource=source;extractionDetails=details;extractedAt=Instant.now();reviewStatus=ReceiptReviewStatus.NEEDS_REVIEW;updatedAt=Instant.now();}
    @Column(length=240) private String suggestedDescription;
    @Column(length=200) private String suggestedMerchant;
    private LocalDate suggestedPurchaseDate;
    @Column(precision=14,scale=2) private BigDecimal suggestedAmount;
    @Column(length=3) private String suggestedCurrency;
    @Enumerated(EnumType.STRING) @Column(length=30) private ReceiptCategory suggestedCategory;
    @Column(nullable=false,length=40) private String extractionStatus="NOT_ATTEMPTED";
    @Column(length=40) private String extractionMethod;
    private Instant extractionTimestamp;
    @Column(length=1000) private String extractionError;
    @Column(length=100) private String extractionModel;
    public void applyExtraction(com.lifeadmin.dto.ReceiptExtractionDetails result) {
        if(reviewStatus==ReceiptReviewStatus.REVIEWED)throw new IllegalStateException("Confirmed receipts cannot be extracted again.");
        suggestedMerchant=result.merchant();suggestedPurchaseDate=result.purchaseDate();suggestedAmount=result.amount();suggestedCurrency=result.currency();suggestedCategory=result.category();suggestedDescription=result.description();
        extractionStatus=result.extractionStatus();extractionMethod=result.extractionMethod();extractionTimestamp=Instant.now();extractionError=result.extractionError();extractionModel=result.extractionModel();
        extractionSource=result.source();extractedAt=extractionTimestamp;reviewStatus=ReceiptReviewStatus.NEEDS_REVIEW;updatedAt=extractionTimestamp;
    }
    public Receipt(UUID owner,ReceiptInput input,String original,String stored,String type,long size) {
        id=UUID.randomUUID();userId=owner;originalFileName=original;storedFileName=stored;contentType=type;fileSize=size;status="SAVED";createdAt=Instant.now();updatedAt=createdAt;currency="USD";reviewStatus=ReceiptReviewStatus.UPLOADED;if(input!=null)update(input);
    }
    public void update(ReceiptInput input) { merchant=input.merchant();purchaseDate=input.purchaseDate();amount=input.amount().setScale(2);currency=input.currency();category=input.category();description=input.description();taxRelevant=input.taxRelevant();notes=input.notes();reviewStatus=ReceiptReviewStatus.REVIEWED;updatedAt=Instant.now(); }
    public void markDeletionPending() {status="DELETION_PENDING";updatedAt=Instant.now();}
}
