package com.booking.movie.service;

import com.booking.movie.client.PaymentGateway;
import com.booking.movie.enums.*;
import com.booking.movie.pojos.*;
import com.booking.movie.service.handler.*;
import com.booking.movie.interceptor.RequestInterceptor;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentJobProcessorTests {
    private final PaymentTransactions transactions = mock(PaymentTransactions.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final OutboxClaims claims = mock(OutboxClaims.class);
    private final PaymentJobProcessor processor = new PaymentJobProcessor();
    private final PaymentReconciliationHandler paymentHandler = new PaymentReconciliationHandler();
    private final RefundHandler refundHandler = new RefundHandler();
    private final Instant now = Instant.parse("2026-09-23T10:00:00Z");
    private final JobClaim claim = JobClaim.builder()
            .id(1L).bookingId(2L).paymentId(3L).type(OutboxType.PAYMENT)
            .token(UUID.randomUUID()).correlationId("stored-correlation").build();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(paymentHandler, "transactions", transactions);
        ReflectionTestUtils.setField(paymentHandler, "gateway", gateway);
        ReflectionTestUtils.setField(paymentHandler, "clock", Clock.fixed(now, ZoneOffset.UTC));
        ReflectionTestUtils.setField(refundHandler, "transactions", transactions);
        ReflectionTestUtils.setField(refundHandler, "gateway", gateway);
        ReflectionTestUtils.setField(processor, "transactions", transactions);
        ReflectionTestUtils.setField(processor, "claims", claims);
        ReflectionTestUtils.setField(processor, "handlers", List.of(paymentHandler, refundHandler));
        ReflectionTestUtils.invokeMethod(processor, "initializeHandlers");
    }

    @AfterEach
    void cleanup() { MDC.clear(); }

    private PaymentWork work(Instant expiry) {
        return PaymentWork.builder().bookingId(2L).amount(BigDecimal.TEN).currency("INR")
                .providerPaymentId("provider-id").holdExpiresAt(expiry).build();
    }

    private ProviderPaymentResponse success() {
        return ProviderPaymentResponse.builder().bookingId(2L).paymentId("provider-id")
                .amount(BigDecimal.TEN).currency("INR").status(ProviderStatus.SUCCESS).build();
    }

    @Test
    void timeoutDefersJobAndRestoresRequestMdc() {
        when(transactions.begin(claim)).thenReturn(work(now.plusSeconds(300)));
        when(gateway.initiate(any())).thenAnswer(call -> {
            assertEquals("stored-correlation", MDC.get(RequestInterceptor.MDC_KEY));
            throw new RuntimeException("timeout");
        });
        MDC.put(RequestInterceptor.MDC_KEY, "request-correlation");
        processor.process(claim, true);
        verify(transactions).retryLater(claim);
        verify(transactions, never()).applyPayment(any(), any());
        assertEquals("request-correlation", MDC.get(RequestInterceptor.MDC_KEY));
    }

    @Test
    void staleClaimNeverCallsProviderAndClearsWorkerMdc() {
        when(transactions.begin(claim)).thenReturn(null);
        processor.process(claim, false);
        verifyNoInteractions(gateway);
        assertNull(MDC.get(RequestInterceptor.MDC_KEY));
    }

    @Test
    void mismatchedProviderAmountIsNotApplied() {
        when(transactions.begin(claim)).thenReturn(work(now.plusSeconds(300)));
        when(gateway.initiate(any())).thenReturn(ProviderPaymentResponse.builder()
                .bookingId(2L).paymentId("provider-id").amount(BigDecimal.ONE)
                .currency("INR").status(ProviderStatus.SUCCESS).build());
        processor.process(claim, true);
        verify(transactions, never()).applyPayment(any(), any());
        verify(transactions).retryLater(claim);
    }

    @Test
    void missingProviderOperationBeforeExpiryIsInitiated() {
        when(transactions.begin(claim)).thenReturn(work(now.plusSeconds(300)));
        when(gateway.fetchStatus(2L)).thenReturn(ProviderPaymentResponse.builder()
                .bookingId(2L).status(ProviderStatus.NOT_FOUND).build());
        when(gateway.initiate(any())).thenReturn(success());
        processor.process(claim, false);
        verify(transactions).applyPayment(claim, success());
    }

    @Test
    void expiredMissingOperationIsNotInitiated() {
        when(transactions.begin(claim)).thenReturn(work(now));
        ProviderPaymentResponse missing = ProviderPaymentResponse.builder()
                .bookingId(2L).status(ProviderStatus.NOT_FOUND).build();
        when(gateway.fetchStatus(2L)).thenReturn(missing);
        processor.process(claim, true);
        verify(gateway, never()).initiate(any());
        verify(transactions).applyPayment(claim, missing);
    }

    @Test
    void paymentFollowUpIsDispatchedToRefundHandler() {
        JobClaim refundClaim = JobClaim.builder().id(4L).bookingId(2L).paymentId(3L)
                .type(OutboxType.REFUND).token(UUID.randomUUID())
                .correlationId("stored-correlation").build();
        when(transactions.begin(claim)).thenReturn(work(now.plusSeconds(300)));
        when(gateway.initiate(any())).thenReturn(success());
        when(transactions.applyPayment(claim, success())).thenReturn(4L);
        when(claims.claimOne(4L)).thenReturn(refundClaim);
        when(transactions.begin(refundClaim)).thenReturn(work(now.plusSeconds(300)));
        ProviderRefundResponse refund = ProviderRefundResponse.builder()
                .bookingId(2L).paymentId("provider-id").refundId("refund-id")
                .amount(BigDecimal.TEN).currency("INR").status(ProviderStatus.SUCCESS).build();
        when(gateway.refund(eq("provider-id"), any())).thenReturn(refund);
        processor.process(claim, true);
        verify(transactions).applyRefund(refundClaim, refund);
        verify(gateway, times(1)).initiate(any());
        assertNull(MDC.get(RequestInterceptor.MDC_KEY));
    }

    @Test
    void invalidRefundResponseIsRetriedWithoutApplyingIt() {
        JobClaim refundClaim = JobClaim.builder().id(4L).bookingId(2L).paymentId(3L)
                .type(OutboxType.REFUND).token(UUID.randomUUID())
                .correlationId("stored-correlation").build();
        when(transactions.begin(refundClaim)).thenReturn(work(now));
        when(gateway.refund(anyString(), any())).thenReturn(ProviderRefundResponse.builder()
                .bookingId(999L).paymentId("provider-id").refundId("refund-id")
                .amount(BigDecimal.TEN).currency("INR").status(ProviderStatus.SUCCESS).build());
        processor.process(refundClaim, false);
        verify(transactions, never()).applyRefund(any(), any());
        verify(transactions).retryLater(refundClaim);
        verify(gateway, never()).initiate(any());
    }

    @Test
    void duplicateOrMissingHandlersFailInitialization() {
        ReflectionTestUtils.setField(processor, "handlers", List.of(paymentHandler, paymentHandler, refundHandler));
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(processor, "initializeHandlers"));
        ReflectionTestUtils.setField(processor, "handlers", List.of(paymentHandler));
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(processor, "initializeHandlers"));
    }
}
