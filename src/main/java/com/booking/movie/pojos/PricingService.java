package com.booking.movie.pojos;

import com.booking.movie.entity.SeatDetails;

import java.time.Instant;
import java.util.List;

public interface PricingService {

    // Applies category/weekend pricing, validates the discount,
    // and returns an immutable quote using explicit rounding.
    PriceQuote quote(
            ShowBookingDetails show,
            List<SeatDetails> seats,
            String discountCode,
            Instant now
    );
}