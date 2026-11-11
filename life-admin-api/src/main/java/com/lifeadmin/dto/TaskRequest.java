package com.lifeadmin.dto;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
public record TaskRequest(@NotBlank @Size(max = 1000) String title,
        @Size(max = 4000) String description,
        @JsonDeserialize(using = StrictLocalDateDeserializer.class) LocalDate dueDate) {}
