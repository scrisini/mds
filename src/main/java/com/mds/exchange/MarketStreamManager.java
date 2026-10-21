package com.mds.exchange;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.mds.config.MdsProperties;
import com.mds.model.ExchangeStatus;
import com.mds.model.MarketPair;
import com.mds.service.QuoteBook;
import info.bitrich.xchangestream.core.ProductSubscription;
import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.core.StreamingMarketDataService;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.functions.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.instrument.Instrument;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Opens a WebSocket connection to each configured exchange and feeds every ticker (and,
 * where needed, trade) update into the {@link QuoteBook}.
 *
 * <p>Failed connections and terminated streams are retried after
 * {@link MdsProperties#reconnectDelay()}; XChange additionally reconnects dropped sockets
 * and resubscribes their channels on its own. Exchanges that cannot be initialised at all
 * (unknown id, metadata download failure) are logged and left out.
 */
@Slf4j
@Component
public class MarketStreamManager implements SmartLifecycle {

    private record Connection(StreamingExchange exchange, List<MarketPair> pairs) {
    }

    private final MdsProperties properties;
    private final QuoteBook quoteBook;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final CompositeDisposable subscriptions = new CompositeDisposable();
    private volatile boolean running;

    public MarketStreamManager(MdsProperties properties, QuoteBook quoteBook) {
        this.properties = properties;
        this.quoteBook = quoteBook;
    }

    @Override
    public void start() {
        List<MarketPair> pairs = properties.pairs().stream().map(MarketPair::parse).toList();
        for (String id : properties.exchanges()) {
            SupportedExchange.fromId(id).ifPresentOrElse(
                    supported -> create(supported).ifPresent(exchange -> {
                        try {
                            attach(supported.id(), exchange, listedPairs(supported.id(), exchange, pairs),
                                    supported.lastPriceFromTrades());
                        } catch (RuntimeException e) {
                            log.error("Could not start streaming from {}", supported.id(), e);
                        }
                    }),
                    () -> log.warn("Ignoring unsupported exchange '{}'", id));
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        subscriptions.dispose();
        connections.forEach((id, connection) -> {
            try {
                connection.exchange().disconnect().blockingAwait(5, TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                log.debug("Error disconnecting from {}: {}", id, e.getMessage());
            }
        });
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Connects and subscribes asynchronously; returns immediately. */
    void attach(String id, StreamingExchange exchange, List<MarketPair> pairs, boolean lastPriceFromTrades) {
        if (pairs.isEmpty()) {
            log.warn("{} lists none of the configured pairs; not connecting", id);
            return;
        }
        connections.put(id, new Connection(exchange, pairs));

        ProductSubscription.ProductSubscriptionBuilder subscription = ProductSubscription.create();
        for (MarketPair pair : pairs) {
            subscription.addTicker(pair.toCurrencyPair());
            if (lastPriceFromTrades) {
                subscription.addTrades(pair.toCurrencyPair());
            }
        }

        ProductSubscription products = subscription.build();
        subscriptions.add(Completable.defer(() -> exchange.connect(products))
                .doOnError(e -> log.warn("Connecting to {} failed, retrying in {}: {}", id, delay(), e.getMessage()))
                .retryWhen(errors -> errors.delay(delay().toMillis(), TimeUnit.MILLISECONDS))
                .subscribe(
                        () -> subscribe(id, exchange, pairs, lastPriceFromTrades),
                        e -> log.error("Giving up on {}", id, e)));
    }

    public List<String> ids() {
        return List.copyOf(connections.keySet());
    }

    public boolean isConfigured(String id) {
        return connections.containsKey(id);
    }

    /** Exchanges that have a subscription for the pair. */
    public List<String> exchangesFor(MarketPair pair) {
        return connections.entrySet().stream()
                .filter(e -> e.getValue().pairs().contains(pair))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    public List<ExchangeStatus> status() {
        return connections.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new ExchangeStatus(e.getKey(), isAlive(e.getValue().exchange()),
                        e.getValue().pairs().stream().map(MarketPair::symbol).toList(),
                        quoteBook.lastUpdate(e.getKey()).orElse(null)))
                .toList();
    }

    // getTicker/getTrades(CurrencyPair) are deprecated in xchange-stream-core 6, but Kraken,
    // Bitstamp, KuCoin and Bitfinex only implement those overloads.
    @SuppressWarnings("deprecation")
    private void subscribe(String id, StreamingExchange exchange, List<MarketPair> pairs, boolean lastPriceFromTrades) {
        log.info("Connected to {}, streaming {}", id, pairs);
        StreamingMarketDataService marketData = exchange.getStreamingMarketDataService();
        for (MarketPair pair : pairs) {
            CurrencyPair currencyPair = pair.toCurrencyPair();
            subscriptions.add(resilient(id, pair, "ticker", () -> marketData.getTicker(currencyPair))
                    .subscribe(ticker -> quoteBook.onTicker(id, pair, ticker)));
            if (lastPriceFromTrades) {
                subscriptions.add(resilient(id, pair, "trades", () -> marketData.getTrades(currencyPair))
                        .subscribe(trade -> quoteBook.onTrade(id, pair, trade.getPrice())));
            }
        }
    }

    /** Re-creates the channel subscription whenever the stream errors or completes. */
    private <T> Observable<T> resilient(String id, MarketPair pair, String channel,
                                        Supplier<Observable<T>> stream) {
        return Observable.defer(stream)
                .doOnError(e -> log.warn("{} {} {} stream failed, resubscribing in {}: {}",
                        id, pair, channel, delay(), e.getMessage()))
                .retryWhen(errors -> errors.delay(delay().toMillis(), TimeUnit.MILLISECONDS))
                .doOnComplete(() -> log.warn("{} {} {} stream ended, resubscribing in {}", id, pair, channel, delay()))
                .repeatWhen(completions -> completions.delay(delay().toMillis(), TimeUnit.MILLISECONDS));
    }

    private Optional<StreamingExchange> create(SupportedExchange supported) {
        try {
            StreamingExchange exchange = (StreamingExchange) ExchangeFactory.INSTANCE
                    .createExchangeWithoutSpecification(supported.exchangeClass());
            ExchangeSpecification spec = exchange.getDefaultExchangeSpecification();
            int timeout = (int) properties.connectionTimeout().toMillis();
            spec.setHttpConnTimeout(timeout);
            spec.setHttpReadTimeout(timeout);
            exchange.applySpecification(spec);
            return Optional.of(exchange);
        } catch (RuntimeException e) {
            log.warn("Could not initialise exchange '{}': {}", supported.id(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Configured pairs the exchange lists. When its instrument metadata is unavailable (Kraken's
     * streaming exchange doesn't load it) every pair is subscribed; unlisted ones simply never update.
     */
    private static List<MarketPair> listedPairs(String id, StreamingExchange exchange, List<MarketPair> pairs) {
        List<Instrument> listed = instruments(exchange);
        if (listed.isEmpty()) {
            log.info("{} provides no instrument list; subscribing to all configured pairs", id);
            return pairs;
        }
        return pairs.stream().filter(p -> listed.contains(p.toCurrencyPair())).toList();
    }

    private static List<Instrument> instruments(StreamingExchange exchange) {
        try {
            List<Instrument> listed = exchange.getExchangeInstruments();
            return listed == null ? List.of() : listed;
        } catch (RuntimeException e) {
            // BaseExchange throws NPE when no metadata was loaded
            return List.of();
        }
    }

    private static boolean isAlive(StreamingExchange exchange) {
        try {
            return exchange.isAlive();
        } catch (RuntimeException e) {
            // some implementations throw before the first connect
            return false;
        }
    }

    private Duration delay() {
        return properties.reconnectDelay();
    }
}
