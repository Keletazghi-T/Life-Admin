package com.lifeadmin.service;
import com.lifeadmin.entity.*;
import com.lifeadmin.dto.ReceiptResponse;
import com.lifeadmin.repository.ReceiptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
@Service
public class ReceiptTaxService {
    private final ReceiptRepository receipts;private final ReceiptSummaryService summaries;private final DocumentStorage storage;
    public ReceiptTaxService(ReceiptRepository receipts,ReceiptSummaryService summaries,DocumentStorage storage){this.receipts=receipts;this.summaries=summaries;this.storage=storage;}
    public record Review(UUID id,String merchant,LocalDate purchaseDate,List<String> reasons){}
    public record Overview(ReceiptSummaryService.Summary summary,List<Review> recordsNeedingReview){}
    public record TaxRow(ReceiptResponse receipt,List<String> reviewReasons){}
    public record Records(List<TaxRow> items,long totalCount,boolean hasMore){}
    private List<Receipt> year(UUID owner,int year){ReceiptService.validateYear(year);var first=LocalDate.of(year,1,1);var result=new ArrayList<>(receipts.findYear(owner,first,first.plusYears(1)));result.addAll(receipts.findUndated(owner,first.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),first.plusYears(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));return result;}
    private List<String> review(Receipt receipt){
        List<String> reasons=new ArrayList<>();
        if(receipt.getReviewStatus()!=ReceiptReviewStatus.REVIEWED)reasons.add("Receipt details need your review");
        if(receipt.getMerchant()==null || receipt.getMerchant().isBlank())reasons.add("Missing merchant");
        if(receipt.getPurchaseDate()==null)reasons.add("Missing purchase date");
        if(receipt.getAmount()==null)reasons.add("Missing amount");
        if(receipt.getCategory()==null)reasons.add("Missing category");
        switch(storage.inspect(receipt.getStoredFileName(),receipt.getFileSize(),receipt.getContentType())) {
            case MISSING -> reasons.add("Receipt file is missing");
            case INVALID -> reasons.add("Receipt file failed its integrity check");
            case UNAVAILABLE -> reasons.add("Receipt file could not be checked; try again later");
            case AVAILABLE -> {}
        }
        return List.copyOf(reasons);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Overview overview(UUID owner,int year){
        var summary=summaries.get(owner,year);var review=new ArrayList<Review>();
        for(var r:year(owner,year)){var reasons=review(r);if(!reasons.isEmpty())review.add(new Review(r.getId(),r.getMerchant(),r.getPurchaseDate(),reasons));}
        return new Overview(summary,List.copyOf(review));
    }
    @Transactional(readOnly=true)
    public Records records(UUID owner,int year,String filter,String sort,int page){
        if(page<0 || page>1_000_000)throw bad("Choose a valid page number.");
        if(!List.of("all","tax-relevant","needs-review").contains(filter))throw bad("Choose a supported receipt filter.");
        Comparator<Receipt> comparator=switch(sort){
            case "date-asc" -> Comparator.comparing(Receipt::getPurchaseDate,Comparator.nullsLast(Comparator.naturalOrder()));
            case "date-desc" -> Comparator.comparing(Receipt::getPurchaseDate,Comparator.nullsLast(Comparator.naturalOrder())).reversed();
            case "merchant" -> Comparator.comparing(Receipt::getMerchant,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "category" -> Comparator.comparing(r->category(r.getCategory()),String.CASE_INSENSITIVE_ORDER);
            case "amount-asc" -> Comparator.comparing(Receipt::getAmount,Comparator.nullsLast(Comparator.naturalOrder()));
            case "amount-desc" -> Comparator.comparing(Receipt::getAmount,Comparator.nullsLast(Comparator.naturalOrder())).reversed();
            default -> throw bad("Choose a supported receipt sort order.");
        };
        var selected=year(owner,year).stream().filter(r->!filter.equals("tax-relevant") || r.isTaxRelevant()).sorted(comparator.thenComparing(Receipt::getId));
        // File checks apply to this account/year only; no file contents are returned here.
        var rows=selected.map(r->new TaxRow(ReceiptResponse.from(r),review(r))).filter(r->!filter.equals("needs-review") || !r.reviewReasons().isEmpty()).toList();
        int start=(int)Math.min((long)page*25,rows.size()),end=Math.min(start+25,rows.size());
        return new Records(rows.subList(start,end),rows.size(),end<rows.size());
    }
    public static String category(ReceiptCategory category){if(category==null)return "Uncategorized";return switch(category){case BUSINESS->"Business";case MEDICAL->"Medical";case CHARITY->"Charity / Donations";case HOME->"Home / Property";case EDUCATION->"Education";case TRANSPORTATION->"Transportation";case MEALS->"Meals";case OFFICE->"Office / Supplies";case OTHER->"Other";};}
    @Transactional(readOnly=true)
    public byte[] csv(UUID owner,int year,String scope){
        if(!List.of("all","tax-relevant").contains(scope))throw bad("Choose all receipts or potentially tax relevant receipts only.");
        StringBuilder csv=new StringBuilder("\uFEFF");
        row(csv,"Purchase Date","Merchant","Amount","Currency","Category","Description","Potentially Tax Relevant","Notes","Original File Name");
        for(var receipt:year(owner,year)){
            if(receipt.getReviewStatus()!=ReceiptReviewStatus.REVIEWED)continue;
            if(scope.equals("tax-relevant") && !receipt.isTaxRelevant())continue;
            row(csv,receipt.getPurchaseDate().toString(),receipt.getMerchant(),receipt.getAmount().toPlainString(),receipt.getCurrency(),category(receipt.getCategory()),receipt.getDescription(),receipt.isTaxRelevant()?"Yes":"No",receipt.getNotes(),receipt.getOriginalFileName());
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void row(StringBuilder out,String... values){for(int i=0;i<values.length;i++){if(i>0)out.append(',');out.append('"').append(safeCell(values[i]).replace("\"","\"\"")).append('"');}out.append("\r\n");}
    /** Quote CSV delimiters, and neutralize spreadsheet formulas without changing stored data. */
    static String safeCell(String value){
        if(value==null)return "";
        boolean formula=false;int first=-1;
        for(int i=0;i<value.length();i++) {char c=value.charAt(i);if(Character.isISOControl(c))formula=true;if(!Character.isWhitespace(c)&&!Character.isSpaceChar(c)&&!Character.isISOControl(c)&&c!='\uFEFF'&&Character.getType(c)!=Character.FORMAT){first=i;break;}}
        if(first>=0 && "=+-@".indexOf(value.charAt(first))>=0)formula=true;
        return formula?"'"+value:value;
    }
    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
