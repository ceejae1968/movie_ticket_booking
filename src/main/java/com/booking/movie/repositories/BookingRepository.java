package com.booking.movie.repositories;

import com.booking.movie.entity.Booking;
import com.booking.movie.enums.BookingStatus;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select booking from Booking booking where booking.id = :id")
    Optional<Booking> findForUpdate(@Param("id") Long id);

    @Query("select seat.seatId from BookingSeat seat where seat.booking.id = :bookingId order by seat.seatId")
    List<Long> findSeatIds(@Param("bookingId") Long bookingId);

    @Query("""
        select count(seat) from BookingSeat seat
        where seat.booking.showScheduleId = :scheduleId
          and seat.booking.id <> :bookingId and seat.seatId in :seatIds
          and (seat.booking.status = :confirmed
               or (seat.booking.status = :held and seat.booking.expiresAt > :now))
        """)
    long countOtherAllocations(@Param("scheduleId") Long scheduleId,
            @Param("bookingId") Long bookingId, @Param("seatIds") Collection<Long> seatIds,
            @Param("confirmed") BookingStatus confirmed, @Param("held") BookingStatus held,
            @Param("now") Instant now);

    @Query("""
        select distinct seat.seatId
        from Booking booking join booking.seats seat
        where booking.showScheduleId = :scheduleId
          and seat.seatId in :seatIds
          and (booking.status = :confirmed
               or (booking.status = :held and booking.expiresAt > :now))
        """)
    List<Long> findUnavailableSeatIds(
            @Param("scheduleId") Long scheduleId,
            @Param("seatIds") Collection<Long> seatIds,
            @Param("confirmed") BookingStatus confirmed,
            @Param("held") BookingStatus held,
            @Param("now") Instant now);
}
