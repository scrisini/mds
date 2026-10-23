package com.mds.model;

/**
 * Published by the quote book whenever an exchange's quote for a pair changes.
 *
 * @param receivedNanos when mds received it ({@link System#nanoTime()}), for timing how long it takes
 *                      to reach subscribers and candles
 */
public record QuoteUpdate(MarketPair pair, PriceQuote quote, long receivedNanos) {
}
