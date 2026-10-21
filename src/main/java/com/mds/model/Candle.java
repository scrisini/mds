package com.mds.model;

import java.time.Instant;

import com.mds.math.Decimal;

/**
 * Open/high/low/close for one pair, source and period.
 *
 * @param source   an exchange id, or {@code index} for the cross-exchange median price
 * @param start    first instant of the period; also the candle's InfluxDB timestamp
 * @param updates  price updates folded into this candle
 * @param complete whether the period has ended; incomplete candles are still changing
 */
public record Candle(
        String pair,
        String source,
        CandlePeriod period,
        Instant start,
        Decimal open,
        Decimal high,
        Decimal low,
        Decimal close,
        long updates,
        boolean complete) {

    public static Candle open(String pair, String source, CandlePeriod period, Instant start, Decimal price) {
        return new Candle(pair, source, period, start, price, price, price, price, 1, false);
    }

    public Candle with(Decimal price) {
        return new Candle(pair, source, period, start, open, high.max(price), low.min(price), price,
                updates + 1, false);
    }

    public Candle completed() {
        return complete ? this : new Candle(pair, source, period, start, open, high, low, close, updates, true);
    }
}
