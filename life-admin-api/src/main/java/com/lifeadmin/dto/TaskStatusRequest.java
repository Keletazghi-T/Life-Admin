package com.lifeadmin.dto;
import com.lifeadmin.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;
public record TaskStatusRequest(@NotNull TaskStatus status) {}
