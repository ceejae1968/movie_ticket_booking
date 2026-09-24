package com.booking.movie.controller;

import com.booking.movie.enums.*;
import com.booking.movie.exception.GlobalExceptionHandler;
import com.booking.movie.interceptor.RequestInterceptor;
import com.booking.movie.pojos.PaymentResponse;
import com.booking.movie.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentControllerTests {
    private final PaymentService service = mock(PaymentService.class);
    private MockMvc mvc;
    private final String request = "{\"bookingId\":501,\"amount\":500.00,\"payMode\":\"UPI\"}";

    @BeforeEach
    void setup() {
        PaymentController controller = new PaymentController();
        ReflectionTestUtils.setField(controller, "paymentService", service);
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(advice, "clock", Clock.systemUTC());
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice)
                .addInterceptors(new RequestInterceptor()).build();
    }

    @Test
    void pendingPaymentReturns202AndCorrelationId() throws Exception {
        when(service.initiate(any(), eq("501"), anyString())).thenReturn(PaymentResponse.builder()
                .id(1L)
                .bookingId(501L)
                .status(PaymentStatus.PENDING)
                .bookingStatus(BookingStatus.IN_PROGRESS)
                .amount(new BigDecimal("500.00"))
                .currency("INR")
                .payMode(PayMode.UPI)
                .refund(null)
                .build());
        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "501")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void missingKeyAndUnsupportedModeAre400() throws Exception {
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.correlationId").isNotEmpty());
        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "501")
                        .contentType(MediaType.APPLICATION_JSON).content(request.replace("UPI", "CARD")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void lateSuccessReturnsStructured409() throws Exception {
        when(service.initiate(any(), anyString(), anyString())).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "Hold expired; refund initiated"));
        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "501")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Hold expired; refund initiated"))
                .andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void statusLookupUsesBookingId() throws Exception {
        when(service.status(501L)).thenReturn(PaymentResponse.builder()
                .id(1L)
                .bookingId(501L)
                .status(PaymentStatus.SUCCEEDED)
                .bookingStatus(BookingStatus.CONFIRMED)
                .amount(new BigDecimal("500.00"))
                .currency("INR")
                .payMode(PayMode.UPI)
                .refund(null)
                .build());
        mvc.perform(get("/api/v1/payments/bookings/501"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"));
        verify(service).status(501L);
    }
}
