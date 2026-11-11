package com.lifeadmin.controller;
import com.lifeadmin.dto.*;
import com.lifeadmin.security.AccountPrincipal;
import com.lifeadmin.service.AccountService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
@RestController @RequestMapping("/api/auth")
public class AuthController {
    private final AccountService accounts; private final AuthenticationManager manager;
    private final SecurityContextRepository contexts; private final SessionAuthenticationStrategy sessions;
    public AuthController(AccountService accounts,AuthenticationManager manager,SecurityContextRepository contexts,SessionAuthenticationStrategy sessions) {
        this.accounts=accounts; this.manager=manager; this.contexts=contexts; this.sessions=sessions;
    }
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("headerName",token.getHeaderName(),"token",token.getToken()); }
    @PostMapping("/register") public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest input,HttpServletRequest request,HttpServletResponse response) {
        accounts.register(input);
        return ResponseEntity.status(HttpStatus.CREATED).body(authenticate(input.email(),input.password(),request,response));
    }
    @PostMapping("/login") public UserResponse login(@Valid @RequestBody LoginRequest input,HttpServletRequest request,HttpServletResponse response) {
        return authenticate(input.email(),input.password(),request,response);
    }
    private UserResponse authenticate(String email,String password,HttpServletRequest request,HttpServletResponse response) {
        try {
            if(password.getBytes(StandardCharsets.UTF_8).length>72) throw new BadCredentialsException("Invalid credentials");
            var authentication=manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email,password));
            sessions.onAuthentication(authentication,request,response);
            var context=SecurityContextHolder.createEmptyContext(); context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context); contexts.saveContext(context,request,response);
            return UserResponse.from((AccountPrincipal)authentication.getPrincipal());
        } catch(AuthenticationException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid email or password."); }
    }
    @org.springframework.beans.factory.annotation.Autowired private com.lifeadmin.repository.UserRepository users;
    @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal AccountPrincipal principal) {
        var user=users.findById(principal.id()).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please log in again."));
        return Map.of("id",user.getId(),"email",user.getEmail(),"deletionPending",user.isDeletionPending());
    }
}
