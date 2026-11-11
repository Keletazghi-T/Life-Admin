package com.lifeadmin.dto;
import jakarta.validation.constraints.*;
public record DeleteAccountRequest(@NotBlank @Size(max=72) String currentPassword,@NotNull @Pattern(regexp="DELETE") String confirmation) {
    @Override public String toString() { return "DeleteAccountRequest[REDACTED]"; }
}
