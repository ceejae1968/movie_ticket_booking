package com.booking.movie.repositories;

import com.booking.movie.entity.ShowSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowScheduleRepository extends JpaRepository<ShowSchedule, Long> {
}
