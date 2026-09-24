package com.booking.movie.pojos;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "booking")
public record BookingProperties(Duration holdDuration) {

    public BookingProperties {
        if (holdDuration == null
                || holdDuration.isZero()
                || holdDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "booking.hold-duration must be positive"
            );
        }
    }
}