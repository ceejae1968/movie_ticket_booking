package com.booking.movie.client;

import com.booking.movie.pojos.ProviderPaymentRequest;
import com.booking.movie.pojos.ProviderPaymentResponse;
import com.booking.movie.pojos.ProviderRefundRequest;
import com.booking.movie.pojos.ProviderRefundResponse;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

// Fictional assignment contract, NOT the real Razorpay API.
@FeignClient(name = "razorpay", url = "${payment.provider.url:http://localhost:8089}")
@Service
public interface RazorpayClient {
    @PostMapping("/payments")
    ProviderPaymentResponse initiate(@RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Correlation-ID") String correlationId,
            @RequestBody ProviderPaymentRequest request);

    @GetMapping("/payments/by-booking/{bookingId}")
    ProviderPaymentResponse status(@PathVariable("bookingId") Long bookingId,
            @RequestHeader("X-Correlation-ID") String correlationId);

    // Repeating this endpoint with the same key retrieves/retries the same refund operation.
    @PostMapping("/payments/{paymentId}/refunds")
    ProviderRefundResponse refund(@PathVariable("paymentId") String paymentId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Correlation-ID") String correlationId,
            @RequestBody ProviderRefundRequest request);
}
