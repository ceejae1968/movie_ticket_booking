package com.booking.movie.repositories;

import com.booking.movie.entity.SeatDetails;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface SeatDetailsRepository extends JpaRepository<SeatDetails, Long> {
    // Lock matching rows in ascending immutable ID order in a single query.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select seat from SeatDetails seat
        where seat.id in :seatIds
        order by seat.id
        """)
    List<SeatDetails> findAllByIdsForUpdate(@Param("seatIds") Collection<Long> seatIds);
}
