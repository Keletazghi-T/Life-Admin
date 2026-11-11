package com.lifeadmin.security;
import com.lifeadmin.repository.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import java.io.IOException;
/** Revalidate persisted identity on every request, including sessions on other application instances. */
public class AccountValidityFilter extends OncePerRequestFilter {
    private final UserRepository users;
    public AccountValidityFilter(UserRepository users) { this.users=users; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return "GET".equals(request.getMethod()) && "/api/health".equals(request.getRequestURI().substring(request.getContextPath().length())); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof AccountPrincipal principal) {
            var user=users.findById(principal.id()).orElse(null);
            if(user==null || user.getSecurityVersion()!=principal.securityVersion()) {
                new SecurityContextLogoutHandler().logout(request,response,auth);
                response.setStatus(401);response.setContentType("application/json");
                response.getWriter().write("{\"message\":\"Your session has expired. Please log in again.\"}");return;
            }
            String path=request.getRequestURI().substring(request.getContextPath().length());
            if(user.isDeletionPending() && !path.equals("/api/account") && !path.startsWith("/api/auth/")) {
                response.setStatus(409);response.setContentType("application/json");
                response.getWriter().write("{\"message\":\"Account deletion is incomplete. Open Settings and retry Delete permanently.\"}");return;
            }
        }
        chain.doFilter(request,response);
    }
}
