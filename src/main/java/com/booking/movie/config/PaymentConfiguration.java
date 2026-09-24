package com.booking.movie.config;

import com.booking.movie.client.RazorpayClient;

import feign.Retryer;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableFeignClients(clients = RazorpayClient.class)
public class PaymentConfiguration {
    @Bean
    public Retryer paymentRetryer() {
        // Durable outbox owns retries, not the HTTP client.
        return Retryer.NEVER_RETRY;
    }
}
