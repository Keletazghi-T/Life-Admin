package com.lifeadmin.dto;
import jakarta.validation.constraints.*;
public record ChangePasswordRequest(@NotBlank @Size(max=72) String currentPassword,
    @NotBlank @Size(min=8,max=72) String newPassword,@NotBlank @Size(min=8,max=72) String confirmNewPassword) {
    @Override public String toString() { return "ChangePasswordRequest[REDACTED]"; }
}
