package com.booking.movie.pojos;

import com.booking.movie.enums.BookingStatus;

import java.time.Instant;
import java.util.UUID;

public record CancellationResponse(
        UUID bookingId,
        BookingStatus status,
        Instant cancelledAt,
        RefundResponse refund // Null when no refund is payable.
) {}