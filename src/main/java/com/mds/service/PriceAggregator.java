package com.mds.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.mds.math.Decimal;
import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.model.PriceQuote;
import com.mds.model.QuoteFailure;
import org.springframework.stereotype.Component;

/**
 * Pure statistics over a set of quotes for one pair; no I/O. Results are rounded to the
 * configured {@link Decimal} scale.
 *
 * <p>This runs for every real-time subscriber on every price change, so it uses plain loops over
 * a sorted array rather than streams, comparators and optionals: the same result with a handful
 * of allocations per call.
 */
@Component
public class PriceAggregator {

    /** Statistics over the quotes; empty when none has a last price. */
    public Optional<AggregatedPrice> aggregate(MarketPair pair, List<PriceQuote> quotes, List<QuoteFailure> failures,
                                               Instant timestamp) {
        Decimal[] lasts = sortedLastPrices(quotes);
        if (lasts.length == 0) {
            return Optional.empty();
        }
        PriceQuote bestBid = best(quotes, PriceQuote::bid, true);
        PriceQuote bestAsk = best(quotes, PriceQuote::ask, false);

        return Optional.of(new AggregatedPrice(
                pair.toString(),
                timestamp,
                lasts.length,
                mean(lasts),
                median(lasts),
                vwap(quotes),
                lasts[0],
                lasts[lasts.length - 1],
                bestBid == null ? null : bestBid.bid(),
                bestBid == null ? null : bestBid.exchange(),
                bestAsk == null ? null : bestAsk.ask(),
                bestAsk == null ? null : bestAsk.exchange(),
                quotes,
                failures));
    }

    private static Decimal[] sortedLastPrices(List<PriceQuote> quotes) {
        Decimal[] lasts = new Decimal[quotes.size()];
        int count = 0;
        for (PriceQuote quote : quotes) {
            if (quote.last() != null) {
                lasts[count++] = quote.last();
            }
        }
        Decimal[] present = count == lasts.length ? lasts : Arrays.copyOf(lasts, count);
        Arrays.sort(present);
        return present;
    }

    private static Decimal mean(Decimal[] values) {
        Decimal sum = Decimal.ZERO;
        for (Decimal value : values) {
            sum = sum.add(value);
        }
        return sum.divide(values.length);
    }

    private static Decimal median(Decimal[] sorted) {
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[mid];
        }
        return sorted[mid - 1].add(sorted[mid]).divide(2);
    }

    /** Null when no exchange reports volume, or the notional exceeds the configured range. */
    private static Decimal vwap(List<PriceQuote> quotes) {
        Decimal notional = Decimal.ZERO;
        Decimal volume = Decimal.ZERO;
        try {
            for (PriceQuote q : quotes) {
                if (q.last() != null && q.volume() != null && q.volume().signum() > 0) {
                    notional = notional.add(q.last().multiply(q.volume()));
                    volume = volume.add(q.volume());
                }
            }
            return volume.isZero() ? null : notional.divide(volume);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * The quote with the highest (or lowest) positive {@code field}, the first one on a tie; null
     * when no quote has one.
     */
    private static PriceQuote best(List<PriceQuote> quotes, Function<PriceQuote, Decimal> field, boolean highest) {
        PriceQuote best = null;
        Decimal bestValue = null;
        for (PriceQuote quote : quotes) {
            Decimal value = field.apply(quote);
            if (value == null || value.signum() <= 0) {
                continue;
            }
            if (bestValue == null || (highest ? value.compareTo(bestValue) > 0 : value.compareTo(bestValue) < 0)) {
                best = quote;
                bestValue = value;
            }
        }
        return best;
    }
}
