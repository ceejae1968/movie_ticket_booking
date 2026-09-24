package com.booking.movie.service;

import com.booking.movie.enums.OutboxType;
import com.booking.movie.interceptor.RequestInterceptor;
import com.booking.movie.pojos.JobClaim;
import com.booking.movie.service.handler.PaymentJobHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentJobProcessor {
    private static final Logger log = LoggerFactory.getLogger(PaymentJobProcessor.class);
    @Autowired
    private PaymentTransactions transactions;
    @Autowired
    private OutboxClaims claims;
    @Autowired
    private List<PaymentJobHandler> handlers;
    private final Map<OutboxType, PaymentJobHandler> handlersByType = new EnumMap<>(OutboxType.class);

    @PostConstruct
    private void initializeHandlers() {
        handlersByType.clear();
        for (PaymentJobHandler handler : handlers) {
            OutboxType type = handler.supports();
            if (type == null || handlersByType.putIfAbsent(type, handler) != null) {
                throw new IllegalStateException("Each outbox type must have exactly one handler");
            }
        }
        for (OutboxType type : OutboxType.values()) {
            if (!handlersByType.containsKey(type)) {
                throw new IllegalStateException("Missing job handler for " + type);
            }
        }
    }

    // No transaction spans strategy execution or provider calls.
    public void process(JobClaim claim, boolean initiate) {
        String previous = MDC.get(RequestInterceptor.MDC_KEY);
        MDC.put(RequestInterceptor.MDC_KEY, claim.correlationId());
        Long followUpJobId = null;
        try {
            followUpJobId = handlersByType.get(claim.type()).process(claim, initiate);
        } catch (RuntimeException exception) {
            log.warn("Payment job {} deferred after {}", claim.id(), exception.getClass().getSimpleName());
            try {
                transactions.retryLater(claim);
            } catch (RuntimeException retryFailure) {
                log.warn("Payment job {} will recover after lease expiry ({})", claim.id(),
                        retryFailure.getClass().getSimpleName());
            }
        } finally {
            if (previous == null) MDC.remove(RequestInterceptor.MDC_KEY);
            else MDC.put(RequestInterceptor.MDC_KEY, previous);
        }
        if (followUpJobId != null) {
            // The handler committed this job; the scheduler recovers any crash here.
            try {
                JobClaim followUp = claims.claimOne(followUpJobId);
                if (followUp != null) process(followUp, false);
            } catch (RuntimeException exception) {
                log.warn("Follow-up job {} awaits scheduled retry", followUpJobId);
            }
        }
    }
}
