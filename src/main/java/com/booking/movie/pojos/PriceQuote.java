package com.booking.movie.pojos;

import java.util.List;
import java.math.BigDecimal;

public record PriceQuote(
        List<SeatPrice> seats,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal total,
        String currency,
        String discountCode
) {}