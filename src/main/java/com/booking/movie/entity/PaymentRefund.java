package com.booking.movie.entity;

import com.booking.movie.enums.RefundStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity
@Table(name = "payment_refunds", uniqueConstraints = @UniqueConstraint(columnNames = "payment_id"))
@Getter @Setter
public class PaymentRefund extends BaseEntity {
    @Column(nullable = false, updatable = false) private Long paymentId;
    @Column(nullable = false, updatable = false) private Long bookingId;
    @Column(nullable = false, updatable = false, precision = 12, scale = 2) private BigDecimal amount;
    @Column(nullable = false, updatable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private RefundStatus status;
    private String providerRefundId;
}
