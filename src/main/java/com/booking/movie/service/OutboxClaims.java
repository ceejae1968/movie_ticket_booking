package com.booking.movie.service;

import com.booking.movie.pojos.JobClaim;

import com.booking.movie.entity.PaymentOutbox;
import com.booking.movie.enums.OutboxStatus;
import com.booking.movie.repositories.PaymentOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.*;
import java.util.*;

@Service
public class OutboxClaims {
    // At most 20 serial jobs, each with up to two HTTP calls (2s connect + 5s read).
    public static final Duration LEASE = Duration.ofMinutes(10);
    @Autowired private PaymentOutboxRepository jobs;
    @Autowired private Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<JobClaim> claimBatch() {
        Instant now = clock.instant();
        return jobs.findBatchForUpdate(now).stream().map(job -> assign(job, now)).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public JobClaim claimOne(Long id) {
        PaymentOutbox job = jobs.findForUpdate(id).orElseThrow();
        Instant now = clock.instant();
        if (job.getStatus() == OutboxStatus.DONE
                || (job.getStatus() == OutboxStatus.PROCESSING && now.isBefore(job.getLeaseUntil()))
                || (job.getStatus() == OutboxStatus.PENDING && now.isBefore(job.getNextAttemptAt()))) {
            return null;
        }
        return assign(job, now);
    }

    public static JobClaim assign(PaymentOutbox job, Instant now) {
        job.setStatus(OutboxStatus.PROCESSING);
        job.setClaimToken(UUID.randomUUID());
        job.setLeaseUntil(now.plus(LEASE));
        job.setAttempts(job.getAttempts() + 1);
        return JobClaim.builder()
                .id(job.getId())
                .bookingId(job.getBookingId())
                .paymentId(job.getPaymentId())
                .type(job.getType())
                .token(job.getClaimToken())
                .correlationId(job.getCorrelationId())
                .build();
    }

    public static boolean owns(PaymentOutbox job, JobClaim claim, Instant now) {
        return job.getStatus() == OutboxStatus.PROCESSING
                && Objects.equals(job.getClaimToken(), claim.token())
                && job.getLeaseUntil() != null && now.isBefore(job.getLeaseUntil());
    }
}
