package com.booking.movie.entity;

import com.booking.movie.enums.BookingStatus;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bookings", indexes = {
    @Index(name = "idx_booking_customer", columnList = "user_id"),
    @Index(name = "idx_booking_schedule_status", columnList = "show_schedule_id,status,expires_at")
})
@Data
public class Booking extends BaseEntity {
    @Column(nullable = false, updatable = false)
    private Long userId;
    @Column(nullable = false, updatable = false)
    private Long showScheduleId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;
    @Column(nullable = false, updatable = false)
    private Instant expiresAt;
    @Column(nullable = false, updatable = false)
    private Instant startAt;
    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal total;
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
    @lombok.ToString.Exclude
    @lombok.EqualsAndHashCode.Exclude
    private List<BookingSeat> seats = new ArrayList<>();

    public void addSeat(BookingSeat seat) {
        seat.setBooking(this);
        seats.add(seat);
    }
}
