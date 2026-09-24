package com.booking.movie.pojos;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(BookingProperties.class)
public class BookingConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}