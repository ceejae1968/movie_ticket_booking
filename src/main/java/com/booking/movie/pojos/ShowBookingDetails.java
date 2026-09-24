package com.booking.movie.pojos;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

public record ShowBookingDetails(
        UUID id,
        Instant startsAt,
        ZoneId theaterZone,
        String currency,
        UUID refundPolicyVersionId
) {}