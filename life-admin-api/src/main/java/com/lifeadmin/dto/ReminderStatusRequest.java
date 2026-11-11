package com.lifeadmin.dto;
import com.lifeadmin.entity.ReminderStatus;
import jakarta.validation.constraints.NotNull;
public record ReminderStatusRequest(@NotNull ReminderStatus status) {}
