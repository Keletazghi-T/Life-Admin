package com.lifeadmin.repository;
import com.lifeadmin.entity.DocumentAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface DocumentAnalysisRepository extends JpaRepository<DocumentAnalysis, UUID> {
    Optional<DocumentAnalysis> findByDocumentId(UUID documentId);
}
