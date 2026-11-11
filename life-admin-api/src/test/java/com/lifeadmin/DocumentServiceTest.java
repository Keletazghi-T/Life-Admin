package com.lifeadmin;
import com.lifeadmin.entity.Document;
import com.lifeadmin.repository.DocumentRepository;
import com.lifeadmin.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DocumentServiceTest {
    @Test void removesFileIfMetadataCommitFails() throws Exception {
        var repository = mock(DocumentRepository.class);
        var storage = mock(DocumentStorage.class);
        when(storage.store(any())).thenReturn("generated.pdf");
        when(repository.saveAndFlush(any(Document.class))).thenThrow(new IllegalStateException("Database unavailable"));
        var users=mock(com.lifeadmin.repository.UserRepository.class);
        when(users.lockById(any())).thenReturn(java.util.Optional.of(new com.lifeadmin.entity.User("test@example.test","hash")));
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var service = new DocumentService(repository, storage, users, manager);
        var file = new MockMultipartFile("file", "letter.pdf", "application/pdf", PdfFixtures.pdf("Test"));
        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), file)).hasMessage("Database unavailable");
        verify(storage).delete("generated.pdf");
    }
}
