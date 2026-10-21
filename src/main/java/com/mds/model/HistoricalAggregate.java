package com.mds.model;

import java.time.Instant;

/** A persisted cross-exchange aggregate row, optionally averaged over a window. */
public record HistoricalAggregate(
        Instant time,
        Double mean,
        Double median,
        Double vwap,
        Double min,
        Double max,
        Double bestBid,
        Double bestAsk,
        Double sources) {
}
