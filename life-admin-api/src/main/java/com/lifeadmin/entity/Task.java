package com.lifeadmin.entity;
import com.lifeadmin.dto.TaskRequest;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.*;
import java.util.UUID;
@Entity
@Table(name = "tasks")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Task {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false) private Document document;
    @Column(nullable = false, length = 1000) private String title;
    @Column(length = 4000) private String description;
    private LocalDate dueDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private TaskStatus status;
    @Column(nullable = false) private boolean developmentMock;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    public Task(Document document, TaskRequest input, boolean developmentMock) {
        this.id = UUID.randomUUID(); this.document = document;
        this.status = TaskStatus.TODO; this.developmentMock = developmentMock;
        this.createdAt = Instant.now();
        update(input);
    }
    public void update(TaskRequest input) {
        title = input.title().strip();
        description = input.description() == null || input.description().isBlank() ? null : input.description().strip();
        dueDate = input.dueDate(); updatedAt = Instant.now();
    }
    public void changeStatus(TaskStatus status) { this.status = status; updatedAt = Instant.now(); }
}
