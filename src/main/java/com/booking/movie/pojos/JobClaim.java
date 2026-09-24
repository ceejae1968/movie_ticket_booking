package com.booking.movie.pojos;

import lombok.Builder;

import com.booking.movie.enums.OutboxType;
import java.util.UUID;

@Builder
public record JobClaim(Long id, Long bookingId, Long paymentId, OutboxType type,
                       UUID token, String correlationId) {}
