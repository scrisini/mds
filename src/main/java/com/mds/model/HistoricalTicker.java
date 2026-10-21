package com.mds.model;

import java.time.Instant;

/** A persisted per-exchange ticker row, optionally averaged over a window. */
public record HistoricalTicker(
        Instant time,
        String exchange,
        Double last,
        Double bid,
        Double ask,
        Double high,
        Double low,
        Double volume) {
}
