package com.booking.movie.pojos;

import java.math.BigDecimal;

public record BookingSeatResponse(Long seatId, String label, String category, BigDecimal price) {
}
