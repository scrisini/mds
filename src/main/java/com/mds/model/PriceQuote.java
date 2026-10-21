package com.mds.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The latest known ticker state for one pair on one exchange. Any price field may be null
 * when the exchange does not publish it.
 *
 * @param timestamp when the most recent update for this quote was received
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

    /**
     * Applies a newer, possibly partial, update: fields it carries replace ours, fields it
     * leaves null keep their previous value.
     */
    public PriceQuote mergedWith(PriceQuote update) {
        return new PriceQuote(exchange, pair,
                coalesce(update.last, last),
                coalesce(update.bid, bid),
                coalesce(update.ask, ask),
                coalesce(update.high, high),
                coalesce(update.low, low),
                coalesce(update.volume, volume),
                coalesce(update.quoteVolume, quoteVolume),
                update.timestamp);
    }

    private static <T> T coalesce(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
