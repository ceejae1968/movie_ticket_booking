package com.booking.movie.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "show_schedule")
@Getter
@Setter
public class ShowSchedule extends BaseEntity {
    @Column(nullable = false, updatable = false)
    private Long showId;
    @Column(nullable = false, updatable = false)
    private Long venueId;
    @Column(nullable = false, updatable = false)
    private Instant startsAt;
    @Column(nullable = false, updatable = false)
    private Instant endsAt;
    @Column(nullable = false)
    private Boolean active;
}
