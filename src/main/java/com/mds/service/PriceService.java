package com.mds.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mds.config.MdsProperties;
import com.mds.exchange.MarketStreamManager;
import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.model.PriceQuote;
import com.mds.model.QuoteFailure;
import org.springframework.stereotype.Service;

/** Aggregates the live, stream-fed quotes held in the {@link QuoteBook}. */
@Service
public class PriceService {

    private final MarketStreamManager streams;
    private final QuoteBook quoteBook;
    private final PriceAggregator aggregator;
    private final List<MarketPair> pairs;
    private final Duration staleAfter;
    private final Clock clock;

    public PriceService(MarketStreamManager streams, QuoteBook quoteBook, PriceAggregator aggregator,
                        MdsProperties properties, Clock clock) {
        this.streams = streams;
        this.quoteBook = quoteBook;
        this.aggregator = aggregator;
        this.pairs = properties.pairs().stream().map(MarketPair::parse).toList();
        this.staleAfter = properties.staleAfter();
        this.clock = clock;
    }

    public List<MarketPair> pairs() {
        return pairs;
    }

    /**
     * Aggregate over every exchange streaming the pair. Exchanges with no update yet, or
     * none within {@code mds.stale-after}, are reported as failures instead.
     */
    public AggregatedPrice aggregate(MarketPair pair) {
        requireSubscribed(pair);
        Instant now = clock.instant();
        Instant freshAfter = now.minus(staleAfter);

        List<PriceQuote> quotes = new ArrayList<>();
        List<QuoteFailure> failures = new ArrayList<>();
        for (String exchange : streams.exchangesFor(pair)) {
            Optional<PriceQuote> quote = quoteBook.latest(exchange, pair);
            if (quote.isEmpty()) {
                failures.add(new QuoteFailure(exchange, "awaiting first update"));
            } else if (quote.get().timestamp().isBefore(freshAfter)) {
                failures.add(new QuoteFailure(exchange, "stale, last update " + quote.get().timestamp()));
            } else {
                quotes.add(quote.get());
            }
        }
        return aggregator.aggregate(pair, quotes, failures, now);
    }

    /** Aggregates for all subscribed pairs that currently have at least one fresh quote. */
    public List<AggregatedPrice> aggregates() {
        List<AggregatedPrice> results = new ArrayList<>();
        for (MarketPair pair : pairs) {
            try {
                results.add(aggregate(pair));
            } catch (PriceUnavailableException e) {
                // no fresh quote yet; omitted until one arrives
            }
        }
        return results;
    }

    /** Latest streamed quote for one exchange, regardless of age. */
    public PriceQuote quote(String exchange, MarketPair pair) {
        String id = exchange.toLowerCase();
        if (!streams.isConfigured(id)) {
            throw new UnknownExchangeException(exchange);
        }
        requireSubscribed(pair);
        return quoteBook.latest(id, pair).orElseThrow(() -> new PriceUnavailableException(pair,
                List.of(new QuoteFailure(id, streams.exchangesFor(pair).contains(id)
                        ? "awaiting first update" : "pair not listed on this exchange"))));
    }

    private void requireSubscribed(MarketPair pair) {
        if (!pairs.contains(pair)) {
            throw new PairNotSubscribedException(pair, pairs);
        }
    }
}
