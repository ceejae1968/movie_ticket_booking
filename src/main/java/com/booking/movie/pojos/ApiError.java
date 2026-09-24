package com.booking.movie.pojos;

import java.time.Instant;

public record ApiError(int status, String message, Instant timestamp, String correlationId) {
}
