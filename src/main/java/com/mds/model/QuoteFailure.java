package com.mds.model;

/** An exchange that was asked for a quote but did not deliver one. */
public record QuoteFailure(String exchange, String reason) {
}
