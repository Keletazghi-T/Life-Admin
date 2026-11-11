package com.lifeadmin.dto;
import com.lifeadmin.entity.*;
import java.time.*;
import java.util.UUID;
public record ReminderResponse(UUID id, UUID taskId, LocalDate reminderDate, ReminderStatus status, boolean actionable, Instant createdAt, Instant updatedAt) {
    public static ReminderResponse from(Reminder r, LocalDate today) {
        Task t=r.getTask();
        boolean active=r.getStatus()==ReminderStatus.PENDING && t.getStatus()==TaskStatus.TODO && t.getDueDate()!=null
            && !r.getReminderDate().isBefore(today) && !r.getReminderDate().isAfter(t.getDueDate());
        return new ReminderResponse(r.getId(),t.getId(),r.getReminderDate(),r.getStatus(),active,r.getCreatedAt(),r.getUpdatedAt());
    }
}
