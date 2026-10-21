package com.mds.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.model.PriceQuote;
import com.mds.model.QuoteFailure;
import org.springframework.stereotype.Component;

/** Pure statistics over a set of quotes for one pair; no I/O. */
@Component
public class PriceAggregator {

    private static final MathContext MC = MathContext.DECIMAL64;

    public AggregatedPrice aggregate(MarketPair pair, List<PriceQuote> quotes, List<QuoteFailure> failures,
                                     Instant timestamp) {
        List<BigDecimal> lasts = quotes.stream()
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

    private static BigDecimal mean(List<BigDecimal> sorted) {
        BigDecimal sum = sorted.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(sorted.size()), MC);
    }

    private static BigDecimal median(List<BigDecimal> sorted) {
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return sorted.get(mid - 1).add(sorted.get(mid)).divide(BigDecimal.TWO, MC);
    }

    private static BigDecimal vwap(List<PriceQuote> quotes) {
        BigDecimal notional = BigDecimal.ZERO;
        BigDecimal volume = BigDecimal.ZERO;
        for (PriceQuote q : quotes) {
            if (q.last() != null && q.volume() != null && q.volume().signum() > 0) {
                notional = notional.add(q.last().multiply(q.volume(), MC));
                volume = volume.add(q.volume());
            }
        }
        return volume.signum() == 0 ? null : notional.divide(volume, MC);
    }

    private static Optional<PriceQuote> best(List<PriceQuote> quotes, Function<PriceQuote, BigDecimal> field,
                                             Comparator<BigDecimal> order) {
        return quotes.stream()
                .filter(q -> field.apply(q) != null && field.apply(q).signum() > 0)
                .min(Comparator.comparing(field, order));
    }
}
