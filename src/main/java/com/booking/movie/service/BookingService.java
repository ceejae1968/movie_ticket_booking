package com.booking.movie.service;

import com.booking.movie.entity.*;
import com.booking.movie.enums.BookingStatus;
import com.booking.movie.exception.InvalidRequestException;
import com.booking.movie.exception.SeatUnavailableException;
import com.booking.movie.pojos.*;
import com.booking.movie.repositories.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    @Autowired
    private UserService userService;
    @Autowired
    private ShowScheduleRepository scheduleRepository;
    @Autowired
    private ShowDetailsRepository showRepository;
    @Autowired
    private SeatDetailsRepository seatRepository;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private BookingProperties properties;
    @Autowired
    private Clock clock;
    @Value("${booking.currency:INR}")
    private String currency;

    // READ_COMMITTED ensures the conflict query sees holds committed by a
    // previous lock owner, even if this transaction began before that commit.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse holdBooking(CreateHoldRequest request) {
        validate(request);
        userService.getUserById(request.userId());
        ShowSchedule schedule = scheduleRepository.findById(request.showScheduleId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Show schedule not found"));
        ShowDetails show = showRepository.findById(schedule.getShowId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Show not found"));
        if (!Boolean.TRUE.equals(schedule.getActive()) || !Boolean.TRUE.equals(show.getActive())) {
            throw new SeatUnavailableException("Show schedule is not open for booking");
        }
        if (schedule.getStartsAt() == null || schedule.getEndsAt() == null
                || !schedule.getEndsAt().isAfter(schedule.getStartsAt())) {
            throw new SeatUnavailableException("Show schedule has invalid timing");
        }

        List<Long> ids = request.seatIds().stream().sorted().toList();
        List<SeatDetails> seats = seatRepository.findAllByIdsForUpdate(ids);
        if (seats.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "One or more seats do not exist");
        }
        for (SeatDetails seat : seats) {
            Long id = seat.getId();
            if (!schedule.getVenueId().equals(seat.getVenueDetails().getId())) {
                throw new InvalidRequestException("Seat does not belong to the scheduled venue: " + id);
            }
            if (!Boolean.TRUE.equals(seat.getEnabled())) {
                throw new SeatUnavailableException("Seat is blocked: " + id);
            }
            if (seat.getPrice() == null || seat.getPrice().signum() < 0) {
                throw new SeatUnavailableException("Seat pricing is not configured: " + id);
            }
        }

        // Evaluate expiry after acquiring every lock; lock waits consume time.
        Instant now = clock.instant();
        if (!now.isBefore(schedule.getStartsAt())) {
            throw new SeatUnavailableException("Show has already started");
        }
        List<Long> unavailable = bookingRepository.findUnavailableSeatIds(
                schedule.getId(), ids, BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS, now);
        if (!unavailable.isEmpty()) {
            throw new SeatUnavailableException("Seats are already held or booked: " + unavailable);
        }

        Instant expiry = now.plus(properties.holdDuration());
        if (expiry.isAfter(schedule.getStartsAt())) {
            expiry = schedule.getStartsAt();
        }
        Booking booking = new Booking();
        booking.setUserId(request.userId());
        booking.setShowScheduleId(schedule.getId());
        booking.setStatus(BookingStatus.IN_PROGRESS);
        booking.setStartAt(now);
        booking.setExpiresAt(expiry);
        booking.setCurrency(currency);
        seats.forEach(seat -> booking.addSeat(new BookingSeat(seat)));
        booking.setTotal(seats.stream().map(SeatDetails::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        bookingRepository.saveAndFlush(booking);
        log.info("Persisted booking hold {}; seats={}", booking.getId(), ids);
        return BookingResponse.from(booking);
    }

    private void validate(CreateHoldRequest request) {
        if (request == null || request.showScheduleId() == null || request.showScheduleId() <= 0
                || request.userId() == null || request.userId() <= 0) {
            throw new InvalidRequestException("showScheduleId and userId must be positive numbers");
        }
        List<Long> ids = request.seatIds();
        if (ids == null || ids.isEmpty() || ids.size() > 8
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new InvalidRequestException("Select between 1 and 8 positive seat IDs");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new InvalidRequestException("Duplicate seat IDs are not allowed");
        }
    }
}
