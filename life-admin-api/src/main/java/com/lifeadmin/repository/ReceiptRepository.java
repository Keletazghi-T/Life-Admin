package com.lifeadmin.repository;
import com.lifeadmin.entity.Receipt;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface ReceiptRepository extends JpaRepository<Receipt,UUID>,JpaSpecificationExecutor<Receipt> {
    @Query("select r from Receipt r where r.userId=:owner and r.status='SAVED' and r.purchaseDate>=:start and r.purchaseDate<:end order by r.purchaseDate,r.id")
    List<Receipt> findYear(@Param("owner") UUID owner,@Param("start") java.time.LocalDate start,@Param("end") java.time.LocalDate end);
    @Query("select r from Receipt r where r.userId=:owner and r.status='SAVED' and r.purchaseDate is null and r.createdAt>=:start and r.createdAt<:end order by r.createdAt,r.id")
    List<Receipt> findUndated(@Param("owner") UUID owner,@Param("start") java.time.Instant start,@Param("end") java.time.Instant end);
    Optional<Receipt> findByUserIdAndUploadRequestId(UUID owner, UUID requestId);
    Optional<Receipt> findByIdAndUserId(UUID id,UUID owner);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from Receipt r where r.id=:id and r.userId=:owner")
    Optional<Receipt> lockOwned(@Param("id") UUID id,@Param("owner") UUID owner);
    @Query("select r.id from Receipt r where r.userId=:owner order by r.id") List<UUID> findIdsByUserId(@Param("owner") UUID owner);
}
