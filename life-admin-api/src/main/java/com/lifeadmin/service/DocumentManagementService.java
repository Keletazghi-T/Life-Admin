package com.lifeadmin.service;
import com.lifeadmin.dto.*;
import com.lifeadmin.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
@Service
public class DocumentManagementService {
    private static final Logger log=LoggerFactory.getLogger(DocumentManagementService.class);
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final TransactionTemplate transactions;
    public DocumentManagementService(DocumentRepository documents,DocumentStorage storage,PlatformTransactionManager manager) {
        this.documents=documents; this.storage=storage; this.transactions=new TransactionTemplate(manager);
    }
    private ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Document not found."); }
    @Transactional public DocumentResponse rename(UUID owner,UUID id,RenameDocumentRequest input) {
        var document=documents.findOwnedForUpdate(id,owner).orElseThrow(this::missing);
        document.rename(input.displayName()); return DocumentResponse.from(document);
    }
    public record OriginalPdf(byte[] content,String filename) {}
    @Transactional public OriginalPdf file(UUID owner,UUID id) {
        // Serializes file reads with deletion, and authorizes before touching storage.
        var document=documents.findOwnedForUpdate(id,owner).orElseThrow(this::missing);
        return new OriginalPdf(storage.read(document.getStoredFileName()),downloadName(document.getDisplayName()));
    }
    static String downloadName(String displayName) {
        String safe=displayName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}\\p{Cf}]","_").replaceAll("^[. ]+|[. ]+$","");
        if(safe.isBlank()) safe="document";
        safe=safe.codePoints().limit(200).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();
        return safe.toLowerCase(Locale.ROOT).endsWith(".pdf") ? safe : safe+".pdf";
    }
    public void delete(UUID owner,UUID id) {
        // Commit a durable marker first. A crash or cleanup failure remains visible and retryable.
        transactions.executeWithoutResult(tx -> documents.findAnyOwnedForUpdate(id,owner).orElseThrow(this::missing).markDeletionPending());
        try {
            transactions.executeWithoutResult(tx -> {
                var document=documents.findAnyOwnedForUpdate(id,owner).orElseThrow(this::missing);
                storage.delete(document.getStoredFileName()); // Idempotent; absent file is already cleaned.
                documents.delete(document);
                documents.flush(); // FK cascades remove analysis, generation ledger, tasks, and reminders.
            });
        } catch (RuntimeException e) {
            // Deliberately omit storage identifiers, paths, content, and exception messages from logs.
            log.error("Document deletion incomplete; retry required for document {}",id);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Document deletion is incomplete. Please retry Delete permanently.");
        }
    }
}
