package com.lifeadmin.dto;
import com.lifeadmin.security.AccountPrincipal;
import java.util.UUID;
public record UserResponse(UUID id,String email) {
    public static UserResponse from(AccountPrincipal p) { return new UserResponse(p.id(),p.getUsername()); }
}
