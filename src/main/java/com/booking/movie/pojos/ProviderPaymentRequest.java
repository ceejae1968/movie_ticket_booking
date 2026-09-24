package com.booking.movie.pojos;

import lombok.Builder;

import java.math.BigDecimal;

// validUntil is an ISO-8601 instant; the assumed provider must reject new charges after it.
@Builder
public record ProviderPaymentRequest(Long bookingId, BigDecimal amount, String currency,
                                     String payMode, String validUntil) {}
