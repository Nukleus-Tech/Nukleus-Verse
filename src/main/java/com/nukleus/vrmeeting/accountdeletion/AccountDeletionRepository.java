package com.nukleus.vrmeeting.accountdeletion;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AccountDeletionRepository extends JpaRepository<AccountDeletionRequest, Long> {
    Optional<AccountDeletionRequest> findFirstByUserIdOrderByRequestedAtDescIdDesc(Long userId);

    @Query("select r.userId from AccountDeletionRequest r where r.id = :id")
    Optional<Long> findOwnerId(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AccountDeletionRequest r where r.id = :id")
    Optional<AccountDeletionRequest> lockById(@Param("id") long id);

    // Advisory locks serialize workers. These candidates are rechecked under row locks.
    @Query(value = """
        select id from account_deletion_requests
        where user_id is not null and scheduled_deletion_at <= :now and
          (status in ('PENDING', 'PROCESSING') or
           (status = 'FAILED' and retry_count < :maxAttempts
            and next_attempt_at <= :now))
        order by scheduled_deletion_at, id
        """, nativeQuery = true)
    List<Long> findDueIds(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts,
                         Pageable pageable);
}
