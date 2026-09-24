package com.booking.movie.entity;

import com.booking.movie.enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity
@Table(name = "payments", uniqueConstraints = @UniqueConstraint(columnNames = "booking_id"))
@Getter @Setter
public class Payment extends BaseEntity {
    @Column(nullable = false, updatable = false) private Long bookingId;
    @Column(nullable = false, updatable = false, precision = 12, scale = 2) private BigDecimal amount;
    @Column(nullable = false, updatable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) private PayMode payMode;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PaymentStatus status;
    private String providerPaymentId;
}
