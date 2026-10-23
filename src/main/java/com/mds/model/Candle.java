package com.mds.model;

import java.time.Instant;
import java.time.ZoneId;
import java.util.function.Consumer;

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

    /**
     * Completes this candle and every following period that has ended by {@code now}, passing each
     * completed candle to {@code completed} and filling every skipped period with a flat candle at the
     * last close. Returns the candle for the period containing {@code now}, or null once the series
     * ends because its source is no longer {@code active}.
     */
    public Candle rolledForward(Instant now, ZoneId zone, boolean active, Consumer<Candle> completed) {
        Candle current = this;
        Instant end = period.end(current.start, zone);
        while (!now.isBefore(end)) {
            completed.accept(current.completed());
            if (!active) {
                return null;
            }
            current = current.carriedForward(end);
            end = period.end(end, zone);
        }
        return current;
    }

    /**
     * Folds a price received at {@code at} into this series: rolls forward to the period containing
     * {@code at} first. A price stamped before the current period (clock skew) is ignored.
     */
    public Candle withPrice(Decimal price, Instant at, ZoneId zone, Consumer<Candle> completed) {
        Candle rolled = rolledForward(at, zone, true, completed);
        return at.isBefore(rolled.start) ? rolled : rolled.with(price);
    }

    public Candle completed() {
        return complete ? this : new Candle(pair, source, period, start, open, high, low, close, updates, true);
    }
}
