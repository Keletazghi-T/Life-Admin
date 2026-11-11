package com.lifeadmin.dto;
import jakarta.validation.constraints.*;
public record LoginRequest(@NotBlank @Size(max=254) String email, @NotBlank @Size(max=72) String password) {
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
