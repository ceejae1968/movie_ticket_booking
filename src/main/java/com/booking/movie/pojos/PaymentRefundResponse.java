package com.booking.movie.pojos;

import lombok.Builder;

import com.booking.movie.enums.RefundStatus;
import java.math.BigDecimal;

@Builder
public record PaymentRefundResponse(Long id, RefundStatus status, BigDecimal amount, String currency) {}
