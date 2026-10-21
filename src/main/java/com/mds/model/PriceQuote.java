package com.mds.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A ticker snapshot from a single exchange. Any price field may be null when the
 * exchange does not publish it.
 */
public record PriceQuote(
        String exchange,
        String pair,
        BigDecimal last,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal high,
        BigDecimal low,
        BigDecimal volume,
        BigDecimal quoteVolume,
        Instant timestamp) {
}
