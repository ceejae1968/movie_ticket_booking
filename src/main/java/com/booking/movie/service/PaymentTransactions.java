package com.booking.movie.service;

import com.booking.movie.entity.*;
import com.booking.movie.enums.*;
import com.booking.movie.exception.InvalidRequestException;
import com.booking.movie.pojos.*;
import com.booking.movie.repositories.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

@Service
public class PaymentTransactions {
    @Autowired private BookingRepository bookings;
    @Autowired private SeatDetailsRepository seats;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentRefundRepository refunds;
    @Autowired private PaymentOutboxRepository jobs;
    @Autowired private Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PreparedPayment prepare(InitiatePaymentRequest request, String key, String correlationId) {
        if (request == null || request.bookingId() == null || request.bookingId() <= 0
                || request.amount() == null || request.amount().signum() <= 0
                || request.amount().stripTrailingZeros().scale() > 2 || request.payMode() != PayMode.UPI) {
            throw new InvalidRequestException("A positive bookingId, positive two-decimal amount and UPI payMode are required");
        }
        if (!request.bookingId().toString().equals(key)) {
            throw new InvalidRequestException("Idempotency-Key must equal bookingId");
        }
        // Never acquire seats after this booking-only lock: no allocation changes here.
        Booking booking = bookings.findForUpdate(request.bookingId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
        Payment existing = payments.findByBookingId(booking.getId()).orElse(null);
        if (existing != null) {
            if (existing.getAmount().compareTo(request.amount()) != 0
                    || existing.getPayMode() != request.payMode()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency key was already used with different payment details");
            }
            return new PreparedPayment(booking.getId(), null);
        }
        Instant now = clock.instant();
        if (booking.getStatus() != BookingStatus.IN_PROGRESS || !now.isBefore(booking.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking hold is no longer valid");
        }
        if (booking.getTotal().compareTo(request.amount()) != 0) {
            throw new InvalidRequestException("Amount must match the booking total");
        }
        Payment payment = new Payment();
        payment.setBookingId(booking.getId());
        payment.setAmount(booking.getTotal());
        payment.setCurrency(booking.getCurrency());
        payment.setPayMode(PayMode.UPI);
        payment.setStatus(PaymentStatus.PENDING);
        payments.saveAndFlush(payment);
        PaymentOutbox job = newJob(payment, booking, OutboxType.PAYMENT, correlationId, now);
        // Flush PENDING before assigning a claim, so the generated job ID is available.
        jobs.saveAndFlush(job);
        JobClaim claim = OutboxClaims.assign(job, now);
        return new PreparedPayment(booking.getId(), claim);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PaymentResponse status(Long bookingId) {
        if (bookingId == null || bookingId <= 0) {
            throw new InvalidRequestException("bookingId must be positive");
        }
        Payment payment = payments.findByBookingId(bookingId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        Booking booking = bookings.findById(bookingId).orElseThrow();
        PaymentRefund refund = refunds.findByPaymentId(payment.getId()).orElse(null);
        BookingStatus status = booking.getStatus();
        if (status == BookingStatus.IN_PROGRESS && !clock.instant().isBefore(booking.getExpiresAt())) {
            status = BookingStatus.EXPIRED;
        }
        return PaymentResponse.builder()
                .id(payment.getId())
                .bookingId(bookingId)
                .status(payment.getStatus())
                .bookingStatus(status)
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .payMode(payment.getPayMode())
                .refund(refund == null ? null :
                PaymentRefundResponse.builder()
                        .id(refund.getId())
                        .status(refund.getStatus())
                        .amount(refund.getAmount())
                        .currency(refund.getCurrency())
                        .build())
                .build();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentWork begin(JobClaim claim) {
        Locked context = lock(claim);
        if (!owns(context, claim)) return null;
        expireHold(context.booking());
        Payment payment = context.payment();
        if (claim.type() == OutboxType.PAYMENT && payment.getStatus() != PaymentStatus.PENDING) {
            done(context.job());
            return null;
        }
        return PaymentWork.builder()
                .bookingId(payment.getBookingId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .providerPaymentId(payment.getProviderPaymentId())
                .holdExpiresAt(context.booking().getExpiresAt())
                .build();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long applyPayment(JobClaim claim, ProviderPaymentResponse result) {
        Locked context = lock(claim);
        if (!owns(context, claim)) return null;
        Booking booking = context.booking();
        Payment payment = context.payment();
        if (payment.getStatus() != PaymentStatus.PENDING) {
            done(context.job());
            return null;
        }
        Instant now = clock.instant();
        expireHold(booking);
        if (result.status() == ProviderStatus.SUCCESS) {
            payment.setStatus(PaymentStatus.SUCCEEDED);
            payment.setProviderPaymentId(result.paymentId());
            boolean validHold = booking.getStatus() == BookingStatus.IN_PROGRESS
                    && now.isBefore(booking.getExpiresAt())
                    && bookings.countOtherAllocations(booking.getShowScheduleId(), booking.getId(),
                        context.seatIds(), BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS, now) == 0;
            if (validHold) {
                booking.setStatus(BookingStatus.CONFIRMED);
                done(context.job());
                return null;
            }
            if (booking.getStatus() == BookingStatus.IN_PROGRESS) booking.setStatus(BookingStatus.RELEASED);
            PaymentRefund refund = new PaymentRefund();
            refund.setPaymentId(payment.getId());
            refund.setBookingId(booking.getId());
            refund.setAmount(payment.getAmount());
            refund.setCurrency(payment.getCurrency());
            refund.setStatus(RefundStatus.PENDING);
            refunds.save(refund);
            PaymentOutbox refundJob = newJob(payment, booking, OutboxType.REFUND, claim.correlationId(), now);
            jobs.saveAndFlush(refundJob);
            done(context.job());
            return refundJob.getId();
        }
        if (result.status() == ProviderStatus.FAILED) {
            payment.setStatus(PaymentStatus.FAILED);
            if (booking.getStatus() == BookingStatus.IN_PROGRESS) booking.setStatus(BookingStatus.RELEASED);
            done(context.job());
        } else {
            retry(context.job(), now);
        }
        return null;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void applyRefund(JobClaim claim, ProviderRefundResponse result) {
        Locked context = lock(claim);
        if (!owns(context, claim)) return;
        PaymentRefund refund = refunds.findByPaymentId(claim.paymentId()).orElseThrow();
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            done(context.job());
            return;
        }
        refund.setProviderRefundId(result.refundId());
        if (result.status() == ProviderStatus.SUCCESS) {
            refund.setStatus(RefundStatus.SUCCEEDED);
            done(context.job());
        } else {
            refund.setStatus(result.status() == ProviderStatus.FAILED ? RefundStatus.FAILED : RefundStatus.PENDING);
            retry(context.job(), clock.instant());
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void retryLater(JobClaim claim) {
        Locked context = lock(claim);
        if (!owns(context, claim)) return;
        expireHold(context.booking());
        retry(context.job(), clock.instant());
    }

    private Locked lock(JobClaim claim) {
        // Allocation mutation order: physical seats -> booking -> payment -> outbox.
        // Query scalar IDs first so an unlocked stale Booking isn't cached by JPA.
        List<Long> ids = bookings.findSeatIds(claim.bookingId());
        if (ids.isEmpty() || seats.findAllByIdsForUpdate(ids).size() != ids.size()) {
            throw new IllegalStateException("Booking seat inventory is inconsistent");
        }
        Booking booking = bookings.findForUpdate(claim.bookingId()).orElseThrow();
        Payment payment = payments.findForUpdate(claim.paymentId()).orElseThrow();
        PaymentOutbox job = jobs.findForUpdate(claim.id()).orElseThrow();
        if (!Objects.equals(job.getPaymentId(), payment.getId())
                || !Objects.equals(payment.getBookingId(), booking.getId())) {
            throw new IllegalStateException("Payment job does not match booking");
        }
        return new Locked(booking, payment, job, ids);
    }

    private boolean owns(Locked context, JobClaim claim) {
        return OutboxClaims.owns(context.job(), claim, clock.instant());
    }

    private void expireHold(Booking booking) {
        if (booking.getStatus() == BookingStatus.IN_PROGRESS
                && !clock.instant().isBefore(booking.getExpiresAt())) {
            booking.setStatus(BookingStatus.EXPIRED);
        }
    }

    private PaymentOutbox newJob(Payment payment, Booking booking, OutboxType type,
                                 String correlationId, Instant now) {
        PaymentOutbox job = new PaymentOutbox();
        job.setPaymentId(payment.getId());
        job.setBookingId(booking.getId());
        job.setType(type);
        job.setStatus(OutboxStatus.PENDING);
        job.setHoldExpiresAt(booking.getExpiresAt());
        job.setNextAttemptAt(now);
        job.setCorrelationId(correlationId == null ? UUID.randomUUID().toString() : correlationId);
        return job;
    }

    private void done(PaymentOutbox job) {
        job.setStatus(OutboxStatus.DONE);
        job.setClaimToken(null);
        job.setLeaseUntil(null);
    }

    private void retry(PaymentOutbox job, Instant now) {
        job.setStatus(OutboxStatus.PENDING);
        job.setClaimToken(null);
        job.setLeaseUntil(null);
        job.setNextAttemptAt(now.plusSeconds(Math.min(300L, 5L << Math.min(job.getAttempts(), 6))));
    }

    private record Locked(Booking booking, Payment payment, PaymentOutbox job, List<Long> seatIds) {}
}
