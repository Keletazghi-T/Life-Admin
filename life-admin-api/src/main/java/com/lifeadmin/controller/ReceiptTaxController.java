package com.lifeadmin.controller;
import com.lifeadmin.security.AccountPrincipal;
import com.lifeadmin.service.ReceiptTaxService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.time.*;
@RestController @RequestMapping("/api/receipts/tax")
public class ReceiptTaxController {
    private final ReceiptTaxService tax;public ReceiptTaxController(ReceiptTaxService tax){this.tax=tax;}
    private int year(Integer year){return year==null?LocalDate.now(ZoneOffset.UTC).getYear():year;}
    @GetMapping public ReceiptTaxService.Overview overview(@AuthenticationPrincipal AccountPrincipal user,@RequestParam(required=false) Integer year){return tax.overview(user.id(),year(year));}
    @GetMapping("/records") public ReceiptTaxService.Records records(@AuthenticationPrincipal AccountPrincipal user,@RequestParam(required=false) Integer year,@RequestParam(defaultValue="all") String filter,@RequestParam(defaultValue="date-desc") String sort,@RequestParam(defaultValue="0") int page){return tax.records(user.id(),year(year),filter,sort,page);}
    @GetMapping("/export") public ResponseEntity<byte[]> export(@AuthenticationPrincipal AccountPrincipal user,@RequestParam(required=false) Integer year,@RequestParam(defaultValue="all") String scope){
        int selected=year(year);byte[] csv=tax.csv(user.id(),selected,scope);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).contentLength(csv.length)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("life-admin-receipts-"+selected+".csv").build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").body(csv);
    }
}
