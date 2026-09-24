package com.booking.movie.client;

import com.booking.movie.interceptor.RequestInterceptor;
import com.booking.movie.pojos.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class RazorpayPaymentGateway implements PaymentGateway {
    @Autowired
    private RazorpayClient client;

    @Override
    public ProviderPaymentResponse initiate(ProviderPaymentRequest request) {
        return client.initiate(request.bookingId().toString(), correlationId(), request);
    }

    @Override
    public ProviderPaymentResponse fetchStatus(Long bookingId) {
        return client.status(bookingId, correlationId());
    }

    @Override
    public ProviderRefundResponse refund(String paymentId, ProviderRefundRequest request) {
        return client.refund(paymentId, "refund-" + request.bookingId(), correlationId(), request);
    }

    private String correlationId() {
        String id = MDC.get(RequestInterceptor.MDC_KEY);
        return id == null ? UUID.randomUUID().toString() : id;
    }
}
