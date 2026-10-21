package com.mds.service;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.mds.config.MdsProperties;
import com.mds.exchange.ExchangeRegistry;
import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.model.PriceQuote;
import com.mds.model.QuoteFailure;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Fetches tickers from the connected exchanges and aggregates them. The most recent
 * aggregate per pair is kept in memory so read endpoints don't hit exchange rate limits.
 */
@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);

    private final ExchangeRegistry registry;
    private final PriceAggregator aggregator;
    private final ExecutorService executor;
    private final Duration fetchTimeout;
    private final List<MarketPair> configuredPairs;
    private final Clock clock;
    private final Map<MarketPair, AggregatedPrice> latest = new ConcurrentHashMap<>();

    @Autowired
    public PriceService(ExchangeRegistry registry, PriceAggregator aggregator, MdsProperties properties,
                        @Qualifier("quoteFetchExecutor") ExecutorService executor) {
        this(registry, aggregator, properties, executor, Clock.systemUTC());
    }

    PriceService(ExchangeRegistry registry, PriceAggregator aggregator, MdsProperties properties,
                 ExecutorService executor, Clock clock) {
        this.registry = registry;
        this.aggregator = aggregator;
        this.executor = executor;
        this.fetchTimeout = properties.fetchTimeout();
        this.configuredPairs = properties.pairs().stream().map(MarketPair::parse).toList();
        this.clock = clock;
    }

    public List<MarketPair> configuredPairs() {
        return configuredPairs;
    }

    /** Cached aggregate for the pair, fetched live on first request. */
    public AggregatedPrice latest(MarketPair pair) {
        AggregatedPrice cached = latest.get(pair);
        return cached != null ? cached : refresh(pair);
    }

    public List<AggregatedPrice> latestForConfiguredPairs() {
        return configuredPairs.stream()
                .map(latest::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Fetches the pair from every exchange that lists it and updates the cache. */
    public AggregatedPrice refresh(MarketPair pair) {
        List<String> exchangeIds = registry.supporting(pair);
        Instant now = clock.instant();

        Map<String, CompletableFuture<PriceQuote>> futures = new LinkedHashMap<>();
        for (String id : exchangeIds) {
            futures.put(id, CompletableFuture
                    .supplyAsync(() -> fetch(id, pair, now), executor)
                    .orTimeout(fetchTimeout.toMillis(), TimeUnit.MILLISECONDS));
        }

        List<PriceQuote> quotes = new ArrayList<>();
        List<QuoteFailure> failures = new ArrayList<>();
        futures.forEach((id, future) -> {
            try {
                quotes.add(future.join());
            } catch (CompletionException e) {
                String reason = describe(e.getCause());
                log.debug("Quote for {} from {} failed: {}", pair, id, reason);
                failures.add(new QuoteFailure(id, reason));
            }
        });

        AggregatedPrice aggregate = aggregator.aggregate(pair, quotes, failures, now);
        latest.put(pair, aggregate);
        return aggregate;
    }

    /** Refreshes every configured pair; pairs that no exchange could price are skipped. */
    public List<AggregatedPrice> refreshConfiguredPairs() {
        List<AggregatedPrice> results = new ArrayList<>();
        for (MarketPair pair : configuredPairs) {
            try {
                results.add(refresh(pair));
            } catch (PriceUnavailableException e) {
                log.warn("{}: {}", e.getMessage(), e.failures());
            }
        }
        return results;
    }

    /** Live quote from a single exchange, bypassing the cache. */
    public PriceQuote quote(String exchangeId, MarketPair pair) {
        String id = exchangeId.toLowerCase();
        if (registry.find(id).isEmpty()) {
            throw new UnknownExchangeException(exchangeId);
        }
        try {
            return CompletableFuture.supplyAsync(() -> fetch(id, pair, clock.instant()), executor)
                    .orTimeout(fetchTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException e) {
            throw new PriceUnavailableException(pair, List.of(new QuoteFailure(id, describe(e.getCause()))));
        }
    }

    // getTicker(CurrencyPair) is deprecated in xchange-core 6, but most exchange modules
    // (Kraken, Bitstamp, KuCoin, Bitfinex) only implement that overload.
    @SuppressWarnings("deprecation")
    private PriceQuote fetch(String exchangeId, MarketPair pair, Instant observedAt) {
        Exchange exchange = registry.find(exchangeId).orElseThrow(() -> new UnknownExchangeException(exchangeId));
        try {
            Ticker t = exchange.getMarketDataService().getTicker(pair.toCurrencyPair());
            return new PriceQuote(exchangeId, pair.toString(), t.getLast(), t.getBid(), t.getAsk(),
                    t.getHigh(), t.getLow(), t.getVolume(), t.getQuoteVolume(), observedAt);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private static String describe(Throwable t) {
        Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        if (cause instanceof TimeoutException) {
            return "timed out";
        }
        String type = cause.getClass().getSimpleName();
        return Optional.ofNullable(cause.getMessage()).map(m -> type + ": " + m).orElse(type);
    }
}
