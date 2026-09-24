package com.booking.movie.service.handler;

import com.booking.movie.enums.OutboxType;
import com.booking.movie.pojos.JobClaim;

public interface PaymentJobHandler {
    OutboxType supports();

    /**
     * Process a claimed job; initiate=true is the first synchronous payment attempt.
     * Return a committed follow-up job ID for immediate processing, or null.
     * Provider calls must remain outside transactions.
     */
    Long process(JobClaim claim, boolean initiate);
}
