package com.lifeadmin.controller;
import com.lifeadmin.service.DashboardService;
import com.lifeadmin.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService dashboard;
    public DashboardController(DashboardService dashboard) {this.dashboard=dashboard;}
    @GetMapping public DashboardService.Summary get(@AuthenticationPrincipal AccountPrincipal user) {return dashboard.get(user.id());}
}
