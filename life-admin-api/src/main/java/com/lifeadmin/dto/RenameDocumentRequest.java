package com.lifeadmin.dto;
import jakarta.validation.constraints.*;
public record RenameDocumentRequest(@NotBlank @Size(max=255)
        @Pattern(regexp="[^\\p{Cntrl}\\p{Cf}]*",message="must not contain control characters") String displayName) {
    public RenameDocumentRequest { if(displayName!=null) displayName=displayName.strip(); }
}
