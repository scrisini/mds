package com.mds.web;

import com.influxdb.exceptions.InfluxException;
import com.mds.logging.LogEvent;
import com.mds.logging.RequestIds;
import com.mds.service.PairNotSubscribedException;
import com.mds.service.PriceUnavailableException;
import com.mds.service.StreamCapacityException;
import com.mds.service.UnknownExchangeException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps domain errors to RFC 9457 problem responses.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler}, so Spring's own errors (unknown path, wrong
 * method, bad parameter) keep their status codes: Spring picks the most specific handler, and
 * only exceptions nothing else handles reach {@link #unexpected}. Every problem body carries the
 * {@code requestId}, which callers quote to support.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return withRequestId(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler({UnknownExchangeException.class, PairNotSubscribedException.class})
    ProblemDetail notFound(RuntimeException e) {
        return withRequestId(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(PriceUnavailableException.class)
    ProblemDetail priceUnavailable(PriceUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setProperty("failures", e.failures());
        return withRequestId(problem);
    }

    @ExceptionHandler(StreamCapacityException.class)
    ProblemDetail streamCapacity(StreamCapacityException e) {
        return withRequestId(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
    }

    @ExceptionHandler(InfluxException.class)
    ProblemDetail influx(InfluxException e, HttpServletRequest request) {
        LogEvent.INFLUX_REQUEST_FAILED.at(log)
                .with("method", request.getMethod())
                .with("path", request.getRequestURI())
                .cause(e)
                .log();
        return withRequestId(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Price archive is unavailable"));
    }

    /** Anything without a more specific handler: logged once with its code and request id; no internals in the response. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e, HttpServletRequest request) {
        LogEvent.UNEXPECTED_ERROR.at(log)
                .with("method", request.getMethod())
                .with("path", request.getRequestURI())
                .cause(e)
                .log();
        return withRequestId(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "unexpected error; quote the requestId to support"));
    }

    /** Adds the request id to the problems Spring builds for its own exceptions. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            withRequestId(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ProblemDetail withRequestId(ProblemDetail problem) {
        String requestId = RequestIds.current();
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }
}
