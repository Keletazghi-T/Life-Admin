package com.lifeadmin.repository;
import com.lifeadmin.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface DocumentRepository extends JpaRepository<Document, UUID> {
    List<Document> findByUserIdOrderByUploadDateDesc(UUID userId, Pageable pageable);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from Document d where d.id = :id and d.userId = :owner and d.status = 'UPLOADED'")
    Optional<Document> findOwnedForUpdate(@org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("owner") UUID owner);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from Document d where d.id=:id and d.userId=:owner")
    Optional<Document> findAnyOwnedForUpdate(@org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("owner") UUID owner);
    @org.springframework.data.jpa.repository.Query("select d from Document d where d.id=:id and d.userId=:owner and d.status='UPLOADED'")
    Optional<Document> findActiveOwned(@org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("owner") UUID owner);
    @org.springframework.data.jpa.repository.Query("select d from Document d where d.userId=:owner and lower(d.displayName) like :pattern escape '!'")
    List<Document> searchOwned(@org.springframework.data.repository.query.Param("owner") UUID owner,
            @org.springframework.data.repository.query.Param("pattern") String pattern, Pageable pageable);
    @org.springframework.data.jpa.repository.Query("select d.id from Document d where d.userId=:owner order by d.id")
    List<UUID> findIdsByUserId(@org.springframework.data.repository.query.Param("owner") UUID owner);
    Optional<Document> findByIdAndUserId(UUID id, UUID userId);
}
