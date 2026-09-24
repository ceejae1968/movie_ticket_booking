package com.booking.movie.scheduler;

import com.booking.movie.pojos.JobClaim;
import com.booking.movie.service.OutboxClaims;
import com.booking.movie.service.PaymentJobProcessor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentOutboxScheduler {
    @Autowired
    private OutboxClaims claims;
    @Autowired
    private PaymentJobProcessor processor;

    @Scheduled(fixedDelayString = "${payment.outbox.poll-delay-ms:5000}")
    public void poll() {
        for (JobClaim claim : claims.claimBatch()) {
            processor.process(claim, false);
        }
    }
}
