package com.lifeadmin.controller;
import com.lifeadmin.dto.*;
import com.lifeadmin.entity.ReceiptCategory;
import com.lifeadmin.service.*;
import com.lifeadmin.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import jakarta.validation.Valid;
import java.util.*;
import java.net.URI;
import java.io.IOException;
import java.time.*;
import java.nio.charset.StandardCharsets;
@RestController @RequestMapping("/api/receipts")
public class ReceiptController {
    private final ReceiptService receipts;private final ReceiptSummaryService summaries;
    public ReceiptController(ReceiptService receipts,ReceiptSummaryService summaries){this.receipts=receipts;this.summaries=summaries;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReceiptResponse> create(@AuthenticationPrincipal AccountPrincipal principal,@RequestPart("file") MultipartFile file,@Valid @RequestPart(value="metadata",required=false) ReceiptInput input,@RequestHeader(value="Idempotency-Key",required=false) UUID requestId) throws IOException {
        var receipt=receipts.create(principal.id(),input,file,requestId);return ResponseEntity.created(URI.create("/api/receipts/"+receipt.id())).body(receipt);
    }
    @GetMapping public ReceiptService.ReceiptPage list(@AuthenticationPrincipal AccountPrincipal principal,@RequestParam(defaultValue="") String search,@RequestParam(required=false) Integer year,@RequestParam(required=false) ReceiptCategory category,@RequestParam(defaultValue="newest") String sort,@RequestParam(defaultValue="0") int page){return receipts.list(principal.id(),search,year,category,sort,page);}
    @GetMapping("/summary") public ReceiptSummaryService.Summary summary(@AuthenticationPrincipal AccountPrincipal principal,@RequestParam(required=false) Integer year){return summaries.get(principal.id(),year==null?LocalDate.now(ZoneOffset.UTC).getYear():year);}
    @GetMapping("/{id}") public ReceiptResponse get(@AuthenticationPrincipal AccountPrincipal principal,@PathVariable UUID id){return receipts.get(principal.id(),id);}
    @PutMapping("/{id}") public ReceiptResponse update(@AuthenticationPrincipal AccountPrincipal principal,@PathVariable UUID id,@Valid @RequestBody ReceiptInput input){return receipts.update(principal.id(),id,input);}
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@AuthenticationPrincipal AccountPrincipal principal,@PathVariable UUID id){receipts.delete(principal.id(),id);return ResponseEntity.noContent().build();}
    @GetMapping("/{id}/content") public ResponseEntity<byte[]> content(@AuthenticationPrincipal AccountPrincipal principal,@PathVariable UUID id,@RequestParam(defaultValue="false") boolean download){
        var file=receipts.content(principal.id(),id);var disposition=(download?ContentDisposition.attachment():ContentDisposition.inline()).filename(file.filename(),StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.type())).contentLength(file.bytes().length).header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString()).header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","frame-ancestors 'self'; base-uri 'none'; form-action 'none'").header("Referrer-Policy","no-referrer").body(file.bytes());
    }
}
