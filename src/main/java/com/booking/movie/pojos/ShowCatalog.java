package com.booking.movie.pojos;

import java.util.UUID;

public interface ShowCatalog {

    // Requires a published show. Published booking configuration
    // must remain immutable for this baseline.
    ShowBookingDetails requireBookableShow(UUID showId);
}