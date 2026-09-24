package com.booking.movie.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "seat_details"
)
@Data
public class SeatDetails extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @Column
    private Boolean enabled;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", insertable = false, updatable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private VenueDetails venueDetails;

    @Column
    private String rowNo;

    @Column
    private String columnNo;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;


}