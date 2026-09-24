package com.booking.movie.pojos;

import lombok.Builder;

import com.booking.movie.enums.ProviderStatus;

import java.math.BigDecimal;

@Builder
public record ProviderPaymentResponse(Long bookingId, String paymentId,
                                     BigDecimal amount, String currency, ProviderStatus status) {}
