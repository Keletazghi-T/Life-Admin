package com.lifeadmin.security;
import java.util.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
/** Only safe identity and Spring's erasable password credentials; never serialize this as an API response. */
public class AccountPrincipal extends org.springframework.security.core.userdetails.User {
    private final UUID id;
    private final long securityVersion;
    public AccountPrincipal(UUID id,String email,String passwordHash) { this(id,email,passwordHash,0); }
    public AccountPrincipal(UUID id,String email,String passwordHash,long securityVersion) {
        super(email,passwordHash,List.of(new SimpleGrantedAuthority("ROLE_USER"))); this.id=id; this.securityVersion=securityVersion;
    }
    public long securityVersion() { return securityVersion; }
    public UUID id() { return id; }
}
