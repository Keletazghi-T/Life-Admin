package com.lifeadmin.repository;
import com.lifeadmin.entity.Reminder;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
import java.time.LocalDate;
public interface ReminderRepository extends JpaRepository<Reminder,UUID> {
    List<Reminder> findByTaskIdOrderByReminderDateAscIdAsc(UUID taskId);
    boolean existsByTaskIdAndReminderDateAndIdNot(UUID taskId, LocalDate date, UUID id);
    @Query("select r.task.id from Reminder r where r.id=:id and r.task.document.userId=:owner")
    Optional<UUID> findOwnedTaskId(@Param("id") UUID id,@Param("owner") UUID owner);
    @Query("select r from Reminder r join fetch r.task t where r.id=:id and t.document.userId=:owner")
    Optional<Reminder> findOwned(@Param("id") UUID id,@Param("owner") UUID owner);
}
