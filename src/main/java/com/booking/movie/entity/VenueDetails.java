package com.booking.movie.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.util.List;

@Entity
@Table(name = "venue")
@Data
public class VenueDetails extends BaseEntity{

    private static final long serialVersionUID = 1L;

    @OneToMany(mappedBy = "venueDetails")
    @lombok.ToString.Exclude
    @lombok.EqualsAndHashCode.Exclude
    private List<SeatDetails> seatDetailsList;
}
