package com.lifeadmin.controller;
import com.lifeadmin.service.ReceiptExtractionWorkflow;
import com.lifeadmin.dto.ReceiptExtractionDetails;
import com.lifeadmin.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/receipts")
public class ReceiptExtractionController {
 private final ReceiptExtractionWorkflow workflow;
 public ReceiptExtractionController(ReceiptExtractionWorkflow workflow){this.workflow=workflow;}
 @GetMapping("/extraction-capabilities") public Map<String,Boolean> capabilities(){return Map.of("extractionEnabled",workflow.available(),"imageExtractionEnabled",workflow.imageExtractionAvailable());}
 @PostMapping("/{id}/extract") public ReceiptExtractionDetails extract(@AuthenticationPrincipal AccountPrincipal principal,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String expectedVersion){return workflow.extract(principal.id(),id,expectedVersion);}
 @GetMapping("/{id}/extraction") public ReceiptExtractionDetails suggestions(@AuthenticationPrincipal AccountPrincipal principal,@PathVariable UUID id){return workflow.suggestions(principal.id(),id);}
}
