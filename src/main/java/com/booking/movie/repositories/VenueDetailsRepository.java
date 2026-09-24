package com.booking.movie.repositories;

import com.booking.movie.entity.VenueDetails;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueDetailsRepository
        extends JpaRepository<VenueDetails, Long> {
}