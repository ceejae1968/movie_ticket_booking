package com.booking.movie.client;

import com.booking.movie.interceptor.RequestInterceptor;
import com.booking.movie.pojos.*;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;

class RazorpayPaymentGatewayTests {
    private final RazorpayClient client = mock(RazorpayClient.class);
    private final RazorpayPaymentGateway gateway = new RazorpayPaymentGateway();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(gateway, "client", client);
        MDC.put(RequestInterceptor.MDC_KEY, "request-123");
    }
    @AfterEach void cleanup() { MDC.clear(); }

    @Test
    void initiationUsesBookingIdAsIdempotencyKey() {
        ProviderPaymentRequest request = ProviderPaymentRequest.builder()
                .bookingId(501L).amount(BigDecimal.TEN).currency("INR")
                .payMode("UPI").validUntil("2026-09-23T10:05:00Z").build();
        gateway.initiate(request);
        verify(client).initiate("501", "request-123", request);
    }

    @Test
    void statusLookupPropagatesCorrelationId() {
        gateway.fetchStatus(501L);
        verify(client).status(501L, "request-123");
    }

    @Test
    void refundReplaysUseSameKeyAndProviderPaymentId() {
        ProviderRefundRequest request = ProviderRefundRequest.builder()
                .bookingId(501L).amount(BigDecimal.TEN).currency("INR").build();
        gateway.refund("pay-501", request);
        gateway.refund("pay-501", request);
        verify(client, times(2)).refund("pay-501", "refund-501", "request-123", request);
    }
}
