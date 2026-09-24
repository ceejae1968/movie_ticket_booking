package com.booking.movie.repositories;

import com.booking.movie.entity.PaymentOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentOutboxRepository extends JpaRepository<PaymentOutbox, Long> {
    @Query(value = """
        select * from payment_outbox
        where (status = 'PENDING' and next_attempt_at <= :now)
           or (status = 'PROCESSING' and lease_until <= :now)
        order by next_attempt_at, id
        limit 20 for update skip locked
        """, nativeQuery = true)
    List<PaymentOutbox> findBatchForUpdate(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from PaymentOutbox job where job.id = :id")
    Optional<PaymentOutbox> findForUpdate(@Param("id") Long id);
}
