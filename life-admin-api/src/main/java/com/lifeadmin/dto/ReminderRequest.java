package com.lifeadmin.dto;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.LocalDate;
public record ReminderRequest(@JsonDeserialize(using=StrictLocalDateDeserializer.class) LocalDate reminderDate, Integer daysBefore) {}
