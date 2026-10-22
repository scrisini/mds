package com.mds.exchange;

import java.net.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.mds.config.MdsProperties;
import com.mds.logging.LogEvent;
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
import si.mazi.rescu.ClientConfig;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Opens a WebSocket connection to each configured exchange and feeds every ticker (and,
 * where needed, trade) update into the {@link QuoteBook}.
 *
 * <p>When {@code mds.socks-proxy.host} is set, every exchange connection goes through that SOCKS5
 * proxy: WebSocket streams via XChange's streaming SOCKS settings, and the REST calls XChange makes
 * while connecting via rescu (whose proxy type is set to SOCKS in {@code rescu.properties}). This
 * fails closed: startup is refused if the REST client would not use SOCKS, and a connection that
 * fails through the proxy is retried through the proxy, never directly.
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
        // XChange builds each REST client from these defaults, then sets only proxy host and port
        verifyProxySupport(new ClientConfig().getProxyType());
        List<MarketPair> pairs = properties.pairs().stream().map(MarketPair::parse).toList();
        for (String id : properties.exchanges()) {
            SupportedExchange.fromId(id).ifPresentOrElse(
                    supported -> create(supported).ifPresent(exchange -> {
                        try {
                            attach(supported.id(), exchange, listedPairs(supported.id(), exchange, pairs),
                                    supported.lastPriceFromTrades());
                        } catch (RuntimeException e) {
                            LogEvent.EXCHANGE_START_FAILED.at(log).with("exchange", supported.id()).cause(e).log();
                        }
                    }),
                    () -> LogEvent.EXCHANGE_UNSUPPORTED.at(log).with("exchange", id)
                            .with("supported", SupportedExchange.ids()).log());
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
            LogEvent.EXCHANGE_NO_CONFIGURED_PAIRS.at(log).with("exchange", id).log();
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
                .doOnError(e -> LogEvent.EXCHANGE_CONNECT_FAILED.at(log).with("exchange", id)
                        .with("retry_in", delay()).with("reason", e.getMessage()).log())
                .retryWhen(errors -> errors.delay(delay().toMillis(), TimeUnit.MILLISECONDS))
                .subscribe(
                        () -> subscribe(id, exchange, pairs, lastPriceFromTrades),
                        e -> LogEvent.EXCHANGE_GAVE_UP.at(log).with("exchange", id).cause(e).log()));
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
        LogEvent.EXCHANGE_CONNECTED.at(log).with("exchange", id).with("pairs", pairs).log();
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
                .doOnError(e -> LogEvent.EXCHANGE_STREAM_FAILED.at(log).with("exchange", id).with("pair", pair)
                        .with("channel", channel).with("retry_in", delay()).with("reason", e.getMessage()).log())
                .retryWhen(errors -> errors.delay(delay().toMillis(), TimeUnit.MILLISECONDS))
                .doOnComplete(() -> LogEvent.EXCHANGE_STREAM_ENDED.at(log).with("exchange", id).with("pair", pair)
                        .with("channel", channel).with("retry_in", delay()).log())
                .repeatWhen(completions -> completions.delay(delay().toMillis(), TimeUnit.MILLISECONDS));
    }

    private Optional<StreamingExchange> create(SupportedExchange supported) {
        try {
            StreamingExchange exchange = (StreamingExchange) ExchangeFactory.INSTANCE
                    .createExchangeWithoutSpecification(supported.exchangeClass());
            ExchangeSpecification spec = exchange.getDefaultExchangeSpecification();
            configure(spec, properties);
            exchange.applySpecification(spec);
            return Optional.of(exchange);
        } catch (RuntimeException e) {
            LogEvent.EXCHANGE_INIT_FAILED.at(log).with("exchange", supported.id()).cause(e).log();
            return Optional.empty();
        }
    }

    /** Applies timeouts and, when configured, the SOCKS proxy for both REST and WebSocket traffic. */
    static void configure(ExchangeSpecification spec, MdsProperties properties) {
        int timeout = (int) properties.connectionTimeout().toMillis();
        spec.setHttpConnTimeout(timeout);
        spec.setHttpReadTimeout(timeout);
        MdsProperties.SocksProxy proxy = properties.socksProxy();
        if (proxy.enabled()) {
            // REST (rescu): host and port here, proxy type SOCKS from rescu.properties
            spec.setProxyHost(proxy.host());
            spec.setProxyPort(proxy.port());
            // WebSocket (netty): read by StreamingExchange.applyStreamingSpecification
            spec.setExchangeSpecificParametersItem(StreamingExchange.SOCKS_PROXY_HOST, proxy.host());
            spec.setExchangeSpecificParametersItem(StreamingExchange.SOCKS_PROXY_PORT, proxy.port());
        }
    }

    /**
     * Refuses to start if a proxy is configured but exchange REST calls would not use SOCKS, which
     * would happen if {@code rescu.properties} were missing or changed.
     */
    void verifyProxySupport(Proxy.Type restProxyType) {
        MdsProperties.SocksProxy proxy = properties.socksProxy();
        if (!proxy.enabled()) {
            LogEvent.EXCHANGE_PROXY_DISABLED.at(log).log();
            return;
        }
        if (restProxyType != Proxy.Type.SOCKS) {
            throw new IllegalStateException("mds.socks-proxy is set but exchange REST calls would use proxy type "
                    + restProxyType + "; rescu.properties must set rescu.http.readProxyType=SOCKS");
        }
        LogEvent.EXCHANGE_PROXY_ENABLED.at(log).with("proxy", proxy).log();
    }

    /**
     * Configured pairs the exchange lists. When its instrument metadata is unavailable (Kraken's
     * streaming exchange doesn't load it) every pair is subscribed; unlisted ones simply never update.
     */
    private static List<MarketPair> listedPairs(String id, StreamingExchange exchange, List<MarketPair> pairs) {
        List<Instrument> listed = instruments(exchange);
        if (listed.isEmpty()) {
            LogEvent.EXCHANGE_NO_INSTRUMENT_LIST.at(log).with("exchange", id).log();
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
