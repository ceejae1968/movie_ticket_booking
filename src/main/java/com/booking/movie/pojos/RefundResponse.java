package com.booking.movie.pojos;

import com.booking.movie.enums.RefundStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record RefundResponse(
        UUID id,
        RefundStatus status,
        BigDecimal amount,
        String currency
) {}