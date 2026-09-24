package com.booking.movie.service.handler;

import com.booking.movie.client.PaymentGateway;
import com.booking.movie.enums.*;
import com.booking.movie.pojos.*;
import com.booking.movie.service.PaymentTransactions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Objects;

@Component
public class RefundHandler implements PaymentJobHandler {
    @Autowired
    private PaymentGateway gateway;
    @Autowired
    private PaymentTransactions transactions;

    @Override
    public OutboxType supports() {
        return OutboxType.REFUND;
    }

    @Override
    public Long process(JobClaim claim, boolean initiate) {
        PaymentWork work = transactions.begin(claim);
        if (work == null) return null;
        ProviderRefundResponse result = gateway.refund(work.providerPaymentId(),
                ProviderRefundRequest.builder()
                        .bookingId(work.bookingId())
                        .amount(work.amount())
                        .currency(work.currency())
                        .build());
        validate(result, work);
        transactions.applyRefund(claim, result);
        return null;
    }

    private void validate(ProviderRefundResponse result, PaymentWork work) {
        if (result == null || result.status() == null
                || !Objects.equals(result.bookingId(), work.bookingId())
                || !Objects.equals(result.paymentId(), work.providerPaymentId())
                || result.amount() == null || result.amount().compareTo(work.amount()) != 0
                || !Objects.equals(result.currency(), work.currency())
                || (result.status() == ProviderStatus.SUCCESS
                    && (result.refundId() == null || result.refundId().isBlank()))) {
            throw new IllegalStateException("Provider refund response does not match payment");
        }
    }
}
