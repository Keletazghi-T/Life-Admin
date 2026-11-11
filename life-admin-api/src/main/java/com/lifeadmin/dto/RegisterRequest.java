package com.lifeadmin.dto;
import jakarta.validation.constraints.*;
public record RegisterRequest(@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(min=8,max=72) String password) {
    public RegisterRequest { if(email!=null) email=email.strip().toLowerCase(java.util.Locale.ROOT); }
    @Override public String toString() { return "RegisterRequest[redacted]"; }
}
