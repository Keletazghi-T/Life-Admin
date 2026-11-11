package com.lifeadmin.controller;
import com.lifeadmin.dto.*;
import com.lifeadmin.security.AccountPrincipal;
import com.lifeadmin.service.AccountManagementService;
import jakarta.validation.Valid;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
@RestController @RequestMapping("/api/account")
public class AccountController {
    private final AccountManagementService accounts;
    public AccountController(AccountManagementService accounts) { this.accounts=accounts; }
    @PutMapping("/password") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal AccountPrincipal principal,@Valid @RequestBody ChangePasswordRequest input,HttpServletRequest req,HttpServletResponse res) {
        accounts.changePassword(principal,input);logout(req,res);
    }
    @DeleteMapping @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AccountPrincipal principal,@Valid @RequestBody DeleteAccountRequest input,HttpServletRequest req,HttpServletResponse res) {
        accounts.delete(principal,input);logout(req,res);
    }
    private void logout(HttpServletRequest req,HttpServletResponse res) {
        new SecurityContextLogoutHandler().logout(req,res,SecurityContextHolder.getContext().getAuthentication());
    }
}
