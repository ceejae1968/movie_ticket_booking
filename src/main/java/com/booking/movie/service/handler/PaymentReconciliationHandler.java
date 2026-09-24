package com.booking.movie.service.handler;

import com.booking.movie.client.PaymentGateway;
import com.booking.movie.enums.*;
import com.booking.movie.pojos.*;
import com.booking.movie.service.PaymentTransactions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.Objects;

@Component
public class PaymentReconciliationHandler implements PaymentJobHandler {
    @Autowired
    private PaymentGateway gateway;
    @Autowired
    private PaymentTransactions transactions;
    @Autowired
    private Clock clock;

    @Override
    public OutboxType supports() {
        return OutboxType.PAYMENT;
    }

    @Override
    public Long process(JobClaim claim, boolean initiate) {
        PaymentWork work = transactions.begin(claim);
        if (work == null) return null;
        ProviderPaymentResponse result;
        if (initiate && clock.instant().isBefore(work.holdExpiresAt())) {
            result = initiate(work);
        } else {
            result = gateway.fetchStatus(work.bookingId());
            validate(result, work);
            // Recover a crash before the original request reached the provider.
            if (result.status() == ProviderStatus.NOT_FOUND
                    && clock.instant().isBefore(work.holdExpiresAt())) {
                result = initiate(work);
            }
        }
        validate(result, work);
        return transactions.applyPayment(claim, result);
    }

    private ProviderPaymentResponse initiate(PaymentWork work) {
        return gateway.initiate(ProviderPaymentRequest.builder()
                .bookingId(work.bookingId())
                .amount(work.amount())
                .currency(work.currency())
                .payMode("UPI")
                .validUntil(work.holdExpiresAt().toString())
                .build());
    }

    private void validate(ProviderPaymentResponse result, PaymentWork work) {
        if (result == null || result.status() == null || !Objects.equals(result.bookingId(), work.bookingId())) {
            throw new IllegalStateException("Provider returned an invalid payment reference");
        }
        if (result.status() != ProviderStatus.NOT_FOUND && (result.amount() == null
                || result.amount().compareTo(work.amount()) != 0
                || !Objects.equals(result.currency(), work.currency()))) {
            throw new IllegalStateException("Provider payment amount or currency mismatch");
        }
        if (result.status() == ProviderStatus.SUCCESS
                && (result.paymentId() == null || result.paymentId().isBlank())) {
            throw new IllegalStateException("Successful payment is missing provider ID");
        }
    }
}
