package com.booking.movie.service;

import com.booking.movie.enums.*;
import com.booking.movie.pojos.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentService {
    @Autowired
    private PaymentTransactions transactions;
    @Autowired
    private PaymentJobProcessor processor;

    public PaymentResponse initiate(InitiatePaymentRequest request, String key, String correlationId) {
        PreparedPayment prepared = transactions.prepare(request, key, correlationId);
        if (prepared.claim() != null) processor.process(prepared.claim(), true);
        PaymentResponse response = transactions.status(prepared.bookingId());
        // Throw only AFTER the outcome/refund transaction has committed.
        if (response.status() == PaymentStatus.SUCCEEDED
                && response.bookingStatus() != BookingStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment succeeded but the hold is no longer valid; a full refund has been initiated");
        }
        return response;
    }

    public PaymentResponse status(Long bookingId) {
        return transactions.status(bookingId);
    }
}
