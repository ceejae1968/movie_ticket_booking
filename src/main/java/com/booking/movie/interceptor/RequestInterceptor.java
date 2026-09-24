package com.booking.movie.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.util.UUID;

@Component
public class RequestInterceptor implements AsyncHandlerInterceptor {

    public static final String CORRELATION_ATTRIBUTE = RequestInterceptor.class.getName() + ".correlationId";
    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    // Also used by advice when MVC rejects a request before preHandle runs.
    public static String correlationId(HttpServletRequest request) {
        Object existing = request.getAttribute(CORRELATION_ATTRIBUTE);
        if (existing instanceof String id) {
            return id;
        }
        String id = UUID.randomUUID().toString();
        request.setAttribute(CORRELATION_ATTRIBUTE, id);
        return id;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String id = correlationId(request);
        MDC.put(MDC_KEY, id);
        response.setHeader(CORRELATION_HEADER, id);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        MDC.remove(MDC_KEY);
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request,
                                               HttpServletResponse response, Object handler) {
        MDC.remove(MDC_KEY);
    }
}
