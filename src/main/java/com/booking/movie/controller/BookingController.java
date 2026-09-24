package com.booking.movie.controller;

import com.booking.movie.pojos.BookingResponse;
import com.booking.movie.pojos.CreateHoldRequest;
import com.booking.movie.service.BookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {
    @Autowired
    private BookingService bookingService;

    @PostMapping("/hold")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse holdBooking(@RequestBody CreateHoldRequest request) {
        return bookingService.holdBooking(request);
    }
}
