package com.booking.movie.repositories;

import com.booking.movie.entity.PaymentRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {
    Optional<PaymentRefund> findByPaymentId(Long paymentId);
}
