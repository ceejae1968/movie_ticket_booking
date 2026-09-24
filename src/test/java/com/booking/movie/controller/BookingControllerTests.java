package com.booking.movie.controller;

import com.booking.movie.exception.*;
import com.booking.movie.interceptor.RequestInterceptor;
import com.booking.movie.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingControllerTests {
    private final BookingService service = mock(BookingService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        BookingController controller = new BookingController();
        ReflectionTestUtils.setField(controller, "bookingService", service);
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(advice, "clock",
                Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice).addInterceptors(new RequestInterceptor()).build();
    }

    @Test
    void seatConflictReturnsStructured409WithMatchingCorrelationId() throws Exception {
        when(service.holdBooking(any())).thenThrow(new SeatUnavailableException("Seat unavailable"));
        var result = mvc.perform(post("/api/v1/bookings/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showScheduleId\":1,\"seatIds\":[2,3],\"userId\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Seat unavailable"))
                .andExpect(jsonPath("$.timestamp").value("2026-09-23T10:00:00Z"))
                .andExpect(header().exists("X-Correlation-ID"))
                .andReturn();
        mvc.perform(post("/api/v1/bookings/hold")
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        String id = result.getResponse().getHeader("X-Correlation-ID");
        assertEquals(id, result.getRequest().getAttribute(RequestInterceptor.CORRELATION_ATTRIBUTE));
        org.springframework.test.util.JsonPathExpectationsHelper path =
                new org.springframework.test.util.JsonPathExpectationsHelper("$.correlationId");
        path.assertValue(result.getResponse().getContentAsString(), id);
    }

    @Test
    void invalidInputHas400AndTimestamp() throws Exception {
        when(service.holdBooking(any())).thenThrow(new InvalidRequestException("Invalid seats"));
        mvc.perform(post("/api/v1/bookings/hold").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showScheduleId\":1,\"seatIds\":[],\"userId\":4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid seats"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
