package com.lifeadmin;
import com.lifeadmin.entity.User;
import com.lifeadmin.repository.UserRepository;
import com.lifeadmin.security.AccountPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import java.util.UUID;
/** Authenticated, persisted user fixtures for domain integration tests. Real login is tested separately. */
abstract class AccountTestSupport {
    @Autowired UserRepository users;
    @Autowired com.lifeadmin.repository.DocumentAnalysisRepository fixtureAnalyses;
    protected void confirmFixtureAnalysis(String id) {
        var analysis=fixtureAnalyses.findByDocumentId(UUID.fromString(id)).orElseThrow();
        analysis.confirm(analysis.suggestions());fixtureAnalyses.saveAndFlush(analysis);
    }
    private static final String FIXTURE_HASH=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("Fixture-only-password");
    protected MockHttpSession account() {
        User user=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test",FIXTURE_HASH));
        var principal=new AccountPrincipal(user.getId(),user.getEmail(),"");
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
        var session=new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        return session;
    }
}
