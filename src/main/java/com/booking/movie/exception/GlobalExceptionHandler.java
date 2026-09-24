package com.booking.movie.exception;

import com.booking.movie.interceptor.RequestInterceptor;
import com.booking.movie.pojos.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Clock;
import org.springframework.dao.PessimisticLockingFailureException;

@ControllerAdvice
@ResponseBody
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Autowired
    private Clock clock;

    @ExceptionHandler(SeatUnavailableException.class)
    public ResponseEntity<Object> handleSeatUnavailable(
            SeatUnavailableException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), new HttpHeaders(), request);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Object> handleLockConflict(
            PessimisticLockingFailureException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "Seats are busy; retry your request",
                new HttpHeaders(), request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Object> handleInvalidRequest(
            InvalidRequestException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), new HttpHeaders(), request);
    }

    // Framework errors (bad JSON, type mismatch, missing parameters, 404, 405,
    // validation, etc.) retain their original status and protocol headers.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest webRequest) {
        HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
        String message = "Request could not be processed";
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        if (knownStatus != null) {
            message = knownStatus.getReasonPhrase();
        }
        if (exception instanceof ResponseStatusException responseStatus
                && responseStatus.getReason() != null) {
            message = responseStatus.getReason();
        }
        if (status.is5xxServerError()) {
            log.error("Request failed; correlationId={}",
                    RequestInterceptor.correlationId(request), exception);
            message = "An unexpected error occurred";
        }
        return error(status, message, headers, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        log.error("Request failed; correlationId={}",
                RequestInterceptor.correlationId(request), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred",
                new HttpHeaders(), request);
    }

    private ResponseEntity<Object> error(HttpStatusCode status, String message,
                                         HttpHeaders originalHeaders,
                                         HttpServletRequest request) {
        String correlationId = RequestInterceptor.correlationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(originalHeaders);
        headers.set(RequestInterceptor.CORRELATION_HEADER, correlationId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(new ApiError(status.value(), message,
                clock.instant(), correlationId), headers, status);
    }
}
