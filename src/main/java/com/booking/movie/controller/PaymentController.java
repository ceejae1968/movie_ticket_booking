package com.booking.movie.controller;

import com.booking.movie.enums.PaymentStatus;
import com.booking.movie.interceptor.RequestInterceptor;
import com.booking.movie.pojos.*;
import com.booking.movie.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    @Autowired private PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse> initiate(
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody InitiatePaymentRequest request, HttpServletRequest servletRequest) {
        PaymentResponse response = paymentService.initiate(request, key,
                RequestInterceptor.correlationId(servletRequest));
        return ResponseEntity.status(response.status() == PaymentStatus.PENDING ? 202 : 200).body(response);
    }

    @GetMapping("/bookings/{bookingId}")
    public PaymentResponse status(@PathVariable Long bookingId) {
        return paymentService.status(bookingId);
    }
}
