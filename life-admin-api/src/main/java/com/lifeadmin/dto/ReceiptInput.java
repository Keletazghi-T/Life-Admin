package com.lifeadmin.dto;
import com.lifeadmin.entity.ReceiptCategory;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
public record ReceiptInput(
    @NotBlank @Size(max=200) String merchant,
    @NotNull @PastOrPresent LocalDate purchaseDate,
    @NotNull @DecimalMin(value="0.00",inclusive=false) @Digits(integer=12,fraction=2) BigDecimal amount,
    @Pattern(regexp="USD",message="Only USD is supported in this milestone") String currency,
    ReceiptCategory category,@Size(max=2000) String description,boolean taxRelevant,@Size(max=4000) String notes) {
    public ReceiptInput { merchant=merchant==null?null:merchant.strip();currency=currency==null?"USD":currency; }
    @AssertTrue(message="Purchase date must be in year 1900 or later") public boolean isPurchaseDateSupported() {return purchaseDate==null || purchaseDate.getYear()>=1900;}
    @Override public String toString() { return "ReceiptInput[private receipt information]"; }
}
