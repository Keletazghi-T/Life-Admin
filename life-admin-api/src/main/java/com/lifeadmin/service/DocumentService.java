package com.lifeadmin.service;
import com.lifeadmin.dto.DocumentResponse;
import com.lifeadmin.entity.Document;
import com.lifeadmin.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
@Service
public class DocumentService {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final DocumentRepository repository;
    private final DocumentStorage storage;
    private final com.lifeadmin.repository.UserRepository users;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public DocumentService(DocumentRepository repository, DocumentStorage storage,com.lifeadmin.repository.UserRepository users,org.springframework.transaction.PlatformTransactionManager manager) {
        this.repository = repository; this.storage = storage;this.users=users;
        this.transactions=new org.springframework.transaction.support.TransactionTemplate(manager);
    }
    public DocumentResponse upload(UUID owner, MultipartFile file) throws IOException {
        if (file.isEmpty()) throw badRequest("Choose a PDF that is not empty.");
        if (file.getSize() > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDFs must be 10 MB or smaller.");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("")
                .replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.length() > 255 || !name.toLowerCase(Locale.ROOT).endsWith(".pdf"))
            throw badRequest("Choose a file with a .pdf extension (filename at most 255 characters).");
        if (!"application/pdf".equalsIgnoreCase(file.getContentType()))
            throw badRequest("Only PDF documents are supported.");
        byte[] content;
        try (var input = file.getInputStream()) { content = input.readNBytes(MAX_BYTES + 1); }
        if (content.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDFs must be 10 MB or smaller.");
        if (content.length < 5 || !new String(content, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
            throw badRequest("This file does not have a valid PDF signature.");
        // Validate the PDF structure, not just the extension or magic bytes.
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(content)) {
            if (pdf.getNumberOfPages() < 1) throw badRequest("Choose a PDF containing at least one page.");
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            // Encrypted PDFs can still be stored/viewed with the user's password; analysis rejects them.
        } catch (IOException | IllegalArgumentException e) {
            throw badRequest("This PDF could not be read. Choose a valid PDF document.");
        }
        String originalName=name;
        String[] storedName={null};
        try {
            return transactions.execute(tx->{
                var user=users.lockById(owner).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please log in again."));
                if(user.isDeletionPending()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Account deletion is incomplete. Open Settings and retry Delete permanently.");
                storedName[0]=storage.store(content);
                return DocumentResponse.from(repository.saveAndFlush(new Document(owner,originalName,storedName[0],content.length)));
            });
        } catch(RuntimeException e) {
            if(storedName[0]!=null) try { storage.delete(storedName[0]); } catch(RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    public List<DocumentResponse> list(UUID owner, int page) { return list(owner,page,"","newest"); }
    public List<DocumentResponse> list(UUID owner,int page,String search,String order) {
        if(search.length()>255) throw badRequest("Search must be at most 255 characters.");
        org.springframework.data.domain.Sort sort=switch(order) {
            case "newest" -> org.springframework.data.domain.Sort.by("uploadDate").descending();
            case "oldest" -> org.springframework.data.domain.Sort.by("uploadDate").ascending();
            case "name-asc" -> org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.asc("displayName").ignoreCase());
            case "name-desc" -> org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("displayName").ignoreCase());
            default -> throw badRequest("Choose a supported document sort order.");
        };
        String pattern="%"+search.strip().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return repository.searchOwned(owner,pattern,PageRequest.of(page,50,sort.and(org.springframework.data.domain.Sort.by("id"))))
                .stream().map(DocumentResponse::from).toList();
    }
    public DocumentResponse get(UUID owner, UUID id) {
        return DocumentResponse.from(repository.findByIdAndUserId(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.")));
    }
    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
