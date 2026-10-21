package com.mds.model;

import java.time.Instant;

import com.mds.math.Decimal;

/**
 * Open/high/low/close for one pair, source and period.
 *
 * @param source   an exchange id, or {@code index} for the cross-exchange median price
 * @param start    first instant of the period; also the candle's InfluxDB timestamp
 * @param updates  price updates folded into this candle; 0 for a period with no updates, whose
 *                 open, high, low and close are all the previous candle's close
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

    /** Folds in a price; the first real price replaces a carried-forward placeholder entirely. */
    public Candle with(Decimal price) {
        if (updates == 0) {
            return open(pair, source, period, start, price);
        }
        return new Candle(pair, source, period, start, open, high.max(price), low.min(price), price,
                updates + 1, false);
    }

    /** A flat placeholder for the period starting at {@code nextStart}, priced at this close. */
    public Candle carriedForward(Instant nextStart) {
        return new Candle(pair, source, period, nextStart, close, close, close, close, 0, false);
    }

    public Candle completed() {
        return complete ? this : new Candle(pair, source, period, start, open, high, low, close, updates, true);
    }
}
