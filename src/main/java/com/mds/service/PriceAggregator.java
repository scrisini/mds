package com.mds.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
 */
@Component
public class PriceAggregator {

    public AggregatedPrice aggregate(MarketPair pair, List<PriceQuote> quotes, List<QuoteFailure> failures,
                                     Instant timestamp) {
        List<Decimal> lasts = quotes.stream()
                .map(PriceQuote::last)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (lasts.isEmpty()) {
            throw new PriceUnavailableException(pair, failures);
        }

        Optional<PriceQuote> bestBid = best(quotes, PriceQuote::bid, Comparator.reverseOrder());
        Optional<PriceQuote> bestAsk = best(quotes, PriceQuote::ask, Comparator.naturalOrder());

        return new AggregatedPrice(
                pair.toString(),
                timestamp,
                lasts.size(),
                mean(lasts),
                median(lasts),
                vwap(quotes),
                lasts.getFirst(),
                lasts.getLast(),
                bestBid.map(PriceQuote::bid).orElse(null),
                bestBid.map(PriceQuote::exchange).orElse(null),
                bestAsk.map(PriceQuote::ask).orElse(null),
                bestAsk.map(PriceQuote::exchange).orElse(null),
                quotes,
                failures);
    }

    private static Decimal mean(List<Decimal> sorted) {
        Decimal sum = sorted.stream().reduce(Decimal.ZERO, Decimal::add);
        return sum.divide(sorted.size());
    }

    private static Decimal median(List<Decimal> sorted) {
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return sorted.get(mid - 1).add(sorted.get(mid)).divide(2);
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

    private static Optional<PriceQuote> best(List<PriceQuote> quotes, Function<PriceQuote, Decimal> field,
                                             Comparator<Decimal> order) {
        return quotes.stream()
                .filter(q -> field.apply(q) != null && field.apply(q).signum() > 0)
                .min(Comparator.comparing(field, order));
    }
}
