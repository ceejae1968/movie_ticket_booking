package com.booking.movie.pojos;

import lombok.Builder;

import com.booking.movie.enums.*;
import java.math.BigDecimal;

@Builder
public record PaymentResponse(Long id, Long bookingId, PaymentStatus status,
        BookingStatus bookingStatus, BigDecimal amount, String currency,
        PayMode payMode, PaymentRefundResponse refund) {}
