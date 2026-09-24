package com.booking.movie.client;

import com.booking.movie.pojos.*;

/** Business-facing provider contract; callers do not depend on Feign. */
public interface PaymentGateway {
    ProviderPaymentResponse initiate(ProviderPaymentRequest request);
    ProviderPaymentResponse fetchStatus(Long bookingId);
    ProviderRefundResponse refund(String paymentId, ProviderRefundRequest request);
}
