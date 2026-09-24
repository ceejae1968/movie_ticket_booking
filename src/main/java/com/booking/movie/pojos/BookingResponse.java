package com.booking.movie.pojos;

import com.booking.movie.entity.Booking;
import com.booking.movie.enums.BookingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BookingResponse(Long id, Long showScheduleId, Long userId,
                              BookingStatus status, Instant heldAt, Instant expiresAt,
                              String currency, BigDecimal total,
                              List<BookingSeatResponse> seats) {
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(booking.getId(), booking.getShowScheduleId(),
                booking.getUserId(), booking.getStatus(), booking.getStartAt(),
                booking.getExpiresAt(), booking.getCurrency(), booking.getTotal(),
                booking.getSeats().stream().map(seat -> new BookingSeatResponse(
                        seat.getSeatId(), seat.getLabel(), seat.getCategory(), seat.getPrice()))
                        .toList());
    }
}
