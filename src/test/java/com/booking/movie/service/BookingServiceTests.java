package com.booking.movie.service;

import com.booking.movie.entity.*;
import com.booking.movie.enums.BookingStatus;
import com.booking.movie.exception.*;
import com.booking.movie.pojos.*;
import com.booking.movie.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingServiceTests {
    private final BookingService service = new BookingService();
    private final UserService users = mock(UserService.class);
    private final ShowScheduleRepository schedules = mock(ShowScheduleRepository.class);
    private final ShowDetailsRepository shows = mock(ShowDetailsRepository.class);
    private final SeatDetailsRepository seats = mock(SeatDetailsRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final Instant now = Instant.parse("2026-09-23T10:00:00Z");
    private ShowSchedule schedule;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "scheduleRepository", schedules);
        ReflectionTestUtils.setField(service, "showRepository", shows);
        ReflectionTestUtils.setField(service, "seatRepository", seats);
        ReflectionTestUtils.setField(service, "bookingRepository", bookings);
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(now, ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "properties", new BookingProperties(Duration.ofMinutes(5)));
        ReflectionTestUtils.setField(service, "currency", "INR");
        schedule = new ShowSchedule();
        schedule.setId(1L);
        schedule.setShowId(2L);
        schedule.setVenueId(3L);
        schedule.setActive(true);
        schedule.setStartsAt(now.plusSeconds(3600));
        schedule.setEndsAt(now.plusSeconds(7200));
        when(schedules.findById(1L)).thenReturn(Optional.of(schedule));
        ShowDetails show = new ShowDetails();
        show.setActive(true);
        when(shows.findById(2L)).thenReturn(Optional.of(show));
        List<SeatDetails> availableSeats = new ArrayList<>();
        for (long id : new long[]{10L, 11L}) {
            SeatDetails seat = new SeatDetails();
            seat.setId(id);
            seat.setVenueId(3L);
            seat.setEnabled(true);
            seat.setPrice(new BigDecimal("100.00"));
            seat.setLabel("A" + id);
            seat.setCategory("REGULAR");
            availableSeats.add(seat);
        }
        when(seats.findAllByIdsForUpdate(anyCollection())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return availableSeats.stream().filter(seat -> ids.contains(seat.getId())).toList();
        });
        when(bookings.findUnavailableSeatIds(anyLong(), anyCollection(), any(), any(), any()))
                .thenReturn(List.of());
        when(bookings.saveAndFlush(any(Booking.class))).thenAnswer(call -> {
            Booking booking = call.getArgument(0);
            booking.setId(99L);
            return booking;
        });
    }

    @Test
    void locksAllSeatsInOneCallThenChecksAndCreatesOneBooking() {
        BookingResponse response = service.holdBooking(new CreateHoldRequest(1L, List.of(11L, 10L), 7L));
        InOrder order = inOrder(seats, bookings);
        order.verify(seats).findAllByIdsForUpdate(List.of(10L, 11L));
        verifyNoMoreInteractions(seats);
        order.verify(bookings).findUnavailableSeatIds(1L, List.of(10L, 11L),
                BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS, now);
        order.verify(bookings).saveAndFlush(any(Booking.class));
        assertEquals(2, response.seats().size());
        assertEquals(new BigDecimal("200.00"), response.total());
        assertEquals(now.plusSeconds(300), response.expiresAt());
        assertEquals(BookingStatus.IN_PROGRESS, response.status());
    }

    @Test
    void missingSeatRejectsEntireRequestBeforeConflictCheckOrSave() {
        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.holdBooking(new CreateHoldRequest(1L, List.of(10L, 999L), 7L)));
        assertEquals(404, exception.getStatusCode().value());
        verifyNoInteractions(bookings);
    }

    @Test
    void seatEntitiesReferenceTheirParentBooking() {
        service.holdBooking(new CreateHoldRequest(1L, List.of(10L, 11L), 7L));
        var captor = org.mockito.ArgumentCaptor.forClass(Booking.class);
        verify(bookings).saveAndFlush(captor.capture());
        Booking booking = captor.getValue();
        assertEquals(2, booking.getSeats().size());
        booking.getSeats().forEach(seat -> assertSame(booking, seat.getBooking()));
    }

    @Test
    void conflictDoesNotSaveAnyBooking() {
        when(bookings.findUnavailableSeatIds(anyLong(), anyCollection(), any(), any(), any()))
                .thenReturn(List.of(11L));
        assertThrows(SeatUnavailableException.class, () -> service.holdBooking(
                new CreateHoldRequest(1L, List.of(10L, 11L), 7L)));
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void duplicateSeatsRejectedBeforeDatabaseAccess() {
        assertThrows(InvalidRequestException.class, () -> service.holdBooking(
                new CreateHoldRequest(1L, List.of(10L, 10L), 7L)));
        verifyNoInteractions(users, schedules, seats, bookings);
    }

    @Test
    void invalidSeatListsAreRejected() {
        for (List<Long> ids : Arrays.asList(Collections.<Long>emptyList(),
                Arrays.asList(10L, null), List.of(-1L),
                List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L))) {
            assertThrows(InvalidRequestException.class,
                    () -> service.holdBooking(new CreateHoldRequest(1L, ids, 7L)));
        }
        assertThrows(InvalidRequestException.class,
                () -> service.holdBooking(new CreateHoldRequest(1L, null, 7L)));
    }

    @Test
    void holdCannotOutliveShowStart() {
        schedule.setStartsAt(now.plusSeconds(60));
        BookingResponse response = service.holdBooking(new CreateHoldRequest(1L, List.of(10L), 7L));
        assertEquals(schedule.getStartsAt(), response.expiresAt());
    }

    @Test
    void seatFromAnotherVenueRejected() {
        schedule.setVenueId(999L);
        assertThrows(InvalidRequestException.class, () -> service.holdBooking(
                new CreateHoldRequest(1L, List.of(10L), 7L)));
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void showAtStartTimeCannotBeBooked() {
        schedule.setStartsAt(now);
        assertThrows(SeatUnavailableException.class, () -> service.holdBooking(
                new CreateHoldRequest(1L, List.of(10L), 7L)));
        verify(bookings, never()).saveAndFlush(any());
    }
}
