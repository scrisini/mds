package com.mds.model;

import java.time.Instant;
import java.util.List;

import com.mds.math.Decimal;

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
        Decimal mean,
        Decimal median,
        Decimal vwap,
        Decimal min,
        Decimal max,
        Decimal bestBid,
        String bestBidExchange,
        Decimal bestAsk,
        String bestAskExchange,
        List<PriceQuote> quotes,
        List<QuoteFailure> failures) {
}
