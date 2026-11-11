package com.lifeadmin.entity;
import jakarta.persistence.*;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name = "task_generations")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class TaskGeneration {
    @Id private UUID analysisId;
    @Column(nullable = false) private Instant createdAt;
    public TaskGeneration(UUID analysisId) { this.analysisId = analysisId; this.createdAt = Instant.now(); }
}
