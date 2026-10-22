package com.mds.web;

import java.io.IOException;

import com.mds.logging.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id ({@link RequestIds}), puts it in the logging context so each log line
 * written while handling the request carries {@code http.request.id}, and returns it in the
 * {@code X-Request-Id} response header. A request is handled on one thread; work handed to other
 * threads must carry the id with {@link RequestIds#bind}. For SSE and WebSocket the id is that of
 * the opening request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = RequestIds.acceptOrGenerate(request.getHeader(RequestIds.HEADER));
        response.setHeader(RequestIds.HEADER, requestId);
        try (RequestIds.Scope ignored = RequestIds.bind(requestId)) {
            chain.doFilter(request, response);
        }
    }
}
