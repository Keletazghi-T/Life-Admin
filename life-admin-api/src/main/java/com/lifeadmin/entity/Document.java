package com.lifeadmin.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name = "documents")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Document {
    @Id private UUID id;
    @Column(nullable = false) private UUID userId;
    @Column(nullable = false) private String originalFileName;
    @Column(nullable = false) private String displayName;
    @Column(nullable = false, unique = true) private String storedFileName;
    @Column(nullable = false, length = 100) private String fileType;
    @Column(nullable = false) private long fileSize;
    @Column(nullable = false) private Instant uploadDate;
    @Column(nullable = false, length = 30) private String status;
    public void rename(String name) { this.displayName=name; }
    public void markDeletionPending() { this.status="DELETION_PENDING"; }
    public Document(UUID userId, String originalFileName, String storedFileName, long fileSize) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.originalFileName = originalFileName;
        this.displayName = originalFileName;
        this.storedFileName = storedFileName;
        this.fileType = "application/pdf";
        this.fileSize = fileSize;
        this.uploadDate = Instant.now();
        this.status = "UPLOADED";
    }
}
