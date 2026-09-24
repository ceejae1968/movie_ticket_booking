package com.booking.movie.pojos;

import java.math.BigDecimal;
import java.util.UUID;

public record SeatPrice(
        UUID showSeatId,
        String label,
        String category,
        BigDecimal price
) {}


