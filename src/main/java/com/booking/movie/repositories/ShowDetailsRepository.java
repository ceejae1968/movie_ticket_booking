package com.booking.movie.repositories;

import com.booking.movie.entity.ShowDetails;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowDetailsRepository
        extends JpaRepository<ShowDetails, Long> {
}