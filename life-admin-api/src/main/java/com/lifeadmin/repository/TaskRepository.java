package com.lifeadmin.repository;
import com.lifeadmin.entity.Task;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface TaskRepository extends JpaRepository<Task, UUID> {
    List<Task> findByDocumentIdAndDocumentUserIdOrderByCreatedAtAscIdAsc(UUID documentId, UUID owner);
    @Query("select new com.lifeadmin.dto.UpcomingTaskResponse(t.id,t.title,t.dueDate,d.id,d.displayName,t.developmentMock) from Task t join t.document d where d.userId=:owner and d.status='UPLOADED' and t.status=com.lifeadmin.entity.TaskStatus.TODO and t.dueDate is not null order by t.dueDate asc,t.id asc")
    List<com.lifeadmin.dto.UpcomingTaskResponse> upcoming(@Param("owner") UUID owner, org.springframework.data.domain.Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :id and t.document.userId = :owner and t.document.status='UPLOADED'")
    Optional<Task> findOwnedForUpdate(@Param("id") UUID id, @Param("owner") UUID owner);
}
