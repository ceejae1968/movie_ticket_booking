package com.booking.movie.interceptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RequestInterceptorTests {

    private final RequestInterceptor interceptor = new RequestInterceptor();

    @AfterEach
    void cleanUpMdc() {
        MDC.clear();
    }

    @Test
    void requestCorrelationIdIsAvailableToLogging() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());
        String id = response.getHeader(RequestInterceptor.CORRELATION_HEADER);
        assertNotNull(id);
        assertEquals(id, MDC.get(RequestInterceptor.MDC_KEY));
        assertEquals(id, request.getAttribute(RequestInterceptor.CORRELATION_ATTRIBUTE));
    }

    @Test
    void completionClearsCorrelationIdAndPreservesOtherMdcEntries() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object handler = new Object();
        MDC.put("otherContext", "preserved");
        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, null);
        assertNull(MDC.get(RequestInterceptor.MDC_KEY));
        assertEquals("preserved", MDC.get("otherContext"));
    }

    @Test
    void failedRequestAlsoClearsCorrelationId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object handler = new Object();
        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, new RuntimeException("failure"));
        assertNull(MDC.get(RequestInterceptor.MDC_KEY));
    }

    @Test
    void asyncHandoffClearsMdcAndRedispatchRestoresSameId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object handler = new Object();
        interceptor.preHandle(request, response, handler);
        String id = MDC.get(RequestInterceptor.MDC_KEY);
        assertNotNull(id);
        interceptor.afterConcurrentHandlingStarted(request, response, handler);
        assertNull(MDC.get(RequestInterceptor.MDC_KEY));
        interceptor.preHandle(request, response, handler);
        assertEquals(id, MDC.get(RequestInterceptor.MDC_KEY));
        interceptor.afterCompletion(request, response, handler, null);
        assertNull(MDC.get(RequestInterceptor.MDC_KEY));
    }

    @Test
    void eachRequestGetsANewServerGeneratedCorrelationId() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.addHeader("X-Correlation-ID", "client-supplied-id");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        interceptor.preHandle(first, firstResponse, new Object());
        interceptor.preHandle(new MockHttpServletRequest(), secondResponse, new Object());
        String firstId = firstResponse.getHeader(RequestInterceptor.CORRELATION_HEADER);
        assertNotNull(firstId);
        assertDoesNotThrow(() -> UUID.fromString(firstId));
        assertNotEquals("client-supplied-id", firstId);
        assertNotEquals(firstId, secondResponse.getHeader(RequestInterceptor.CORRELATION_HEADER));
    }

    @Test
    void redispatchKeepsTheSameCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());
        String firstId = response.getHeader(RequestInterceptor.CORRELATION_HEADER);
        interceptor.preHandle(request, response, new Object());
        assertEquals(firstId, response.getHeader(RequestInterceptor.CORRELATION_HEADER));
    }

}
