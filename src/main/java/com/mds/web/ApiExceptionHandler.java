package com.mds.web;

import com.influxdb.exceptions.InfluxException;
import com.mds.service.PriceUnavailableException;
import com.mds.service.UnknownExchangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain errors to RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(UnknownExchangeException.class)
    ProblemDetail unknownExchange(UnknownExchangeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PriceUnavailableException.class)
    ProblemDetail priceUnavailable(PriceUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setProperty("failures", e.failures());
        return problem;
    }

    @ExceptionHandler(InfluxException.class)
    ProblemDetail influx(InfluxException e) {
        log.error("InfluxDB request failed", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Price archive is unavailable");
    }
}
