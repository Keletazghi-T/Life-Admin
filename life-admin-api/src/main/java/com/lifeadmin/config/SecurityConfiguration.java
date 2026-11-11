package com.lifeadmin.config;
import com.lifeadmin.repository.UserRepository;
import com.lifeadmin.security.AccountPrincipal;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.authentication.session.*;
import org.springframework.http.HttpMethod;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService userDetailsService(UserRepository users) {
        return email -> users.findByEmail(email.strip().toLowerCase(Locale.ROOT))
            .map(u -> new AccountPrincipal(u.getId(),u.getEmail(),u.getPasswordHash(),u.getSecurityVersion()))
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
    @Bean AuthenticationManager authenticationManager(UserDetailsService users,PasswordEncoder encoder) {
        var provider=new DaoAuthenticationProvider(users); provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }
    @Bean SessionAuthenticationStrategy loginSessionStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(),new CsrfAuthenticationStrategy(csrf)));
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,SecurityContextRepository context,CsrfTokenRepository csrf,UserRepository users) throws Exception {
        http.addFilterBefore(new com.lifeadmin.security.AccountValidityFilter(users),org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        http.cors(org.springframework.security.config.Customizer.withDefaults());
        http.securityContext(c -> c.securityContextRepository(context))
            .csrf(c -> c.csrfTokenRepository(csrf))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET,"/api/auth/csrf","/api/health").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/auth/register","/api/auth/login").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .requestCache(c -> c.disable())
            .formLogin(c -> c.disable()).httpBasic(c -> c.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> error(res,401,"Please log in to continue."))
                .accessDeniedHandler((req,res,ex) -> error(res,403,"Request not permitted. Refresh the page and try again.")))
            .logout(l -> l.logoutUrl("/api/auth/logout").invalidateHttpSession(true).clearAuthentication(true)
                .deleteCookies("JSESSIONID").logoutSuccessHandler((req,res,auth) -> res.setStatus(204)));
        // Exact configured browser origin only; authentication and CSRF still apply.
        return http.build();
    }
    private static void error(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"message\":\""+message+"\"}");
    }
}
