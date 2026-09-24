package com.booking.movie.pojos;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record PaymentWork(Long bookingId, BigDecimal amount, String currency,
                          String providerPaymentId, Instant holdExpiresAt) {}
