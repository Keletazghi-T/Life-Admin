package com.lifeadmin.dto;
import com.lifeadmin.entity.Document;
import java.time.Instant;
import java.util.UUID;
public record DocumentResponse(UUID id, String originalFileName, String displayName, String fileType,
        long fileSize, Instant uploadDate, String status) {
    public static DocumentResponse from(Document d) {
        return new DocumentResponse(d.getId(), d.getOriginalFileName(), d.getDisplayName(), d.getFileType(),
                d.getFileSize(), d.getUploadDate(), d.getStatus());
    }
}
