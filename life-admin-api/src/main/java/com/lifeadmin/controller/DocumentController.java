package com.lifeadmin.controller;
import com.lifeadmin.dto.DocumentResponse;
import com.lifeadmin.dto.RenameDocumentRequest;
import jakarta.validation.Valid;
import com.lifeadmin.service.DocumentManagementService;
import java.nio.charset.StandardCharsets;
import com.lifeadmin.service.DocumentService;
import com.lifeadmin.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.constraints.Min;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.net.URI;
import java.util.*;
@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService service;
    private final DocumentManagementService management;
    public DocumentController(DocumentService service, DocumentManagementService management) { this.service = service; this.management=management; }
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal AccountPrincipal principal) throws IOException {
        DocumentResponse document = service.upload(principal.id(), file);
        return ResponseEntity.created(URI.create("/api/documents/" + document.id())).body(document);
    }
    @GetMapping public List<DocumentResponse> list(@AuthenticationPrincipal AccountPrincipal principal, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue="") String search,@RequestParam(defaultValue="newest") String sort) {
        return service.list(principal.id(), page, search, sort);
    }
    @GetMapping("/{id}") public DocumentResponse get(@PathVariable UUID id, @AuthenticationPrincipal AccountPrincipal principal) {
        return service.get(principal.id(), id);
    }
    @GetMapping("/{id}/file") public ResponseEntity<byte[]> file(@PathVariable UUID id,
            @RequestParam(defaultValue="false") boolean download,@AuthenticationPrincipal AccountPrincipal principal) {
        var pdf=management.file(principal.id(),id);
        var disposition=(download ? ContentDisposition.attachment() : ContentDisposition.inline()).filename(pdf.filename(),StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(pdf.content().length)
            .header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString())
            .header(HttpHeaders.CACHE_CONTROL,"private, no-store")
            .header("X-Content-Type-Options","nosniff")
            .header("Content-Security-Policy","frame-ancestors 'self'; base-uri 'none'; form-action 'none'")
            .header("Referrer-Policy","no-referrer").body(pdf.content());
    }
    @PatchMapping("/{id}") public DocumentResponse rename(@PathVariable UUID id,@Valid @RequestBody RenameDocumentRequest input,
            @AuthenticationPrincipal AccountPrincipal principal) { return management.rename(principal.id(),id,input); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal principal) {
        management.delete(principal.id(),id); return ResponseEntity.noContent().build();
    }
}
