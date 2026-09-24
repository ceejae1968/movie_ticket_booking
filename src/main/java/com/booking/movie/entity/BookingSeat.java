package com.booking.movie.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Entity
@Table(name = "booking_seats", uniqueConstraints = @UniqueConstraint(
        name = "uk_booking_seats_booking_seat", columnNames = {"booking_id", "seat_id"}))
@Data
@NoArgsConstructor
public class BookingSeat extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    @lombok.ToString.Exclude
    @lombok.EqualsAndHashCode.Exclude
    private Booking booking;

    @Column(name = "seat_id", nullable = false, updatable = false)
    private Long seatId;
    @Column(nullable = false, updatable = false)
    private String label;
    @Column(nullable = false, updatable = false)
    private String category;
    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal price;

    public BookingSeat(SeatDetails seat) {
        this.seatId = seat.getId();
        this.label = seat.getLabel();
        this.category = seat.getCategory();
        this.price = seat.getPrice();
    }
}
