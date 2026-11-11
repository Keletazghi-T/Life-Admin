package com.lifeadmin.controller;

import com.lifeadmin.security.AccountPrincipal;
import com.lifeadmin.service.ReceiptInsightsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.*;

@RestController
@RequestMapping("/api/receipts/insights")
public class ReceiptInsightsController {
    private final ReceiptInsightsService insights;
    public ReceiptInsightsController(ReceiptInsightsService insights) { this.insights=insights; }
    @GetMapping
    public ReceiptInsightsService.Insights get(@AuthenticationPrincipal AccountPrincipal user,
        @RequestParam(required=false) Integer year) {
        return insights.get(user.id(),year==null?LocalDate.now(ZoneOffset.UTC).getYear():year);
    }
}
