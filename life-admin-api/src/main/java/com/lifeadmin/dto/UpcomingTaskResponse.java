package com.lifeadmin.dto;
import java.time.LocalDate;
import java.util.UUID;
public record UpcomingTaskResponse(UUID taskId,String taskTitle,LocalDate dueDate,UUID documentId,String documentName,boolean developmentMock) {}
