package com.lifeadmin.dto;
import com.lifeadmin.entity.*;
import java.time.*;
import java.util.UUID;
public record TaskResponse(UUID id, UUID documentId, String title, String description, LocalDate dueDate,
        TaskStatus status, boolean developmentMock, Instant createdAt, Instant updatedAt) {
    public static TaskResponse from(Task task) {
        return new TaskResponse(task.getId(), task.getDocument().getId(), task.getTitle(), task.getDescription(),
                task.getDueDate(), task.getStatus(), task.isDevelopmentMock(), task.getCreatedAt(), task.getUpdatedAt());
    }
}
