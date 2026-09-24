package com.booking.movie.pojos;

import java.util.List;

public record CreateHoldRequest(Long showScheduleId, List<Long> seatIds, Long userId) {
}
