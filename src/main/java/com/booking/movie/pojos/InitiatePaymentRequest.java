package com.booking.movie.pojos;

import com.booking.movie.enums.PayMode;
import java.math.BigDecimal;

public record InitiatePaymentRequest(Long bookingId, BigDecimal amount, PayMode payMode) {}
