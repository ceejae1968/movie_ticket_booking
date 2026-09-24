package com.booking.movie.pojos;

import lombok.Builder;
import java.math.BigDecimal;
@Builder
public record ProviderRefundRequest(Long bookingId, BigDecimal amount, String currency) {}
