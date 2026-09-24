package com.booking.movie.entity;

import com.booking.movie.enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_outbox", uniqueConstraints = @UniqueConstraint(columnNames = {"payment_id", "type"}))
@Getter @Setter
public class PaymentOutbox extends BaseEntity {
    @Column(nullable = false, updatable = false) private Long bookingId;
    @Column(nullable = false, updatable = false) private Long paymentId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) private OutboxType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private OutboxStatus status;
    @Column(nullable = false, updatable = false) private Instant holdExpiresAt;
    @Column(nullable = false) private Instant nextAttemptAt;
    private Instant leaseUntil;
    private UUID claimToken;
    @Column(nullable = false) private int attempts;
    @Column(nullable = false, updatable = false) private String correlationId;
}
