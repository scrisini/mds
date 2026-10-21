package com.mds.service;

public class UnknownExchangeException extends RuntimeException {

    public UnknownExchangeException(String exchange) {
        super("Exchange '%s' is not configured".formatted(exchange));
    }
}
