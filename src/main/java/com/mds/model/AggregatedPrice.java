package com.mds.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Cross-exchange view of one pair at a point in time.
 *
 * @param vwap        last prices weighted by each exchange's 24h base volume; null when no
 *                    exchange reported volume
 * @param bestBid     highest bid across exchanges
 * @param bestAsk     lowest ask across exchanges
 */
public record AggregatedPrice(
        String pair,
        Instant timestamp,
        int sources,
        BigDecimal mean,
        BigDecimal median,
        BigDecimal vwap,
        BigDecimal min,
        BigDecimal max,
        BigDecimal bestBid,
        String bestBidExchange,
        BigDecimal bestAsk,
        String bestAskExchange,
        List<PriceQuote> quotes,
        List<QuoteFailure> failures) {
}
