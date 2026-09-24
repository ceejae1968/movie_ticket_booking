package com.booking.movie.pojos;

import lombok.Builder;

import com.booking.movie.enums.ProviderStatus;
import java.math.BigDecimal;
@Builder
public record ProviderRefundResponse(Long bookingId, String paymentId, String refundId,
                                    BigDecimal amount, String currency, ProviderStatus status) {}
