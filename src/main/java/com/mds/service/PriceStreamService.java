package com.mds.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.mds.config.MdsProperties;
import com.mds.exchange.MarketStreamManager;
import com.mds.logging.LogEvent;
import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.model.QuoteUpdate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes live aggregates to real-time subscribers over Server-Sent Events and WebSocket.
 *
 * <p>Each subscription has a {@link StreamRate}: at most N updates per second (or one per interval),
 * or unthrottled. Throttling uses latest-value semantics: the current aggregate is sent at once,
 * then at most one update per interval, always the most recent state. Unthrottled subscriptions get
 * every change. Either way, a client that can't keep up is sent the latest aggregate when it
 * catches up, never a backlog.
 *
 * <p>Metrics, all tagged {@code transport} ({@code sse}/{@code websocket}):
 * <ul>
 *   <li>{@code mds.stream.subscribers} gauge, and {@code mds.stream.events} counter (also tagged {@code pair});</li>
 *   <li>{@code mds.stream.latency} timer: from receiving the newest exchange update an event reflects to
 *       handing the event to the client's connection, including any wait for the client's rate;</li>
 *   <li>{@code mds.stream.queue} timer: from the moment the client's rate let the event go to handing it
 *       over, which grows when send threads or connections fall behind.</li>
 * </ul>
 *
 * <p>Every open stream also gets a heartbeat every {@code mds.stream.heartbeat}, so load balancers
 * and proxies don't close a connection while its pair is quiet: one ticker for all of them, with
 * each connection sending on its own thread like its prices.
 *
 * <p>On shutdown it stops first, before the exchange streams, and closes every subscription as
 * {@linkplain #shed shedding} does. Otherwise the web server's graceful shutdown would wait for
 * streams that never end, while their clients received no prices.
 */
@Slf4j
@Service
public class PriceStreamService implements SmartLifecycle {

    public static final String SSE = "sse";
    public static final String WEBSOCKET = "websocket";
    /** Stops before {@link MarketStreamManager#PHASE}, while prices still flow. */
    public static final int PHASE = DEFAULT_PHASE;

    private final QuoteBook quoteBook;
    private final PriceService priceService;
    private final MdsProperties.Stream settings;
    private final MeterRegistry registry;
    private final Scheduler throttleScheduler;
    private final Scheduler sendScheduler;
    private final AtomicInteger subscribers = new AtomicInteger();
    private final AtomicLong opened = new AtomicLong();
    // by opening order, so shedding can pick the newest
    private final ConcurrentSkipListMap<Long, Subscription> openByAge = new ConcurrentSkipListMap<>();
    private final Map<String, AtomicInteger> subscribersByTransport = new ConcurrentHashMap<>();
    private final Map<String, StreamTimers> timersByTransport = new ConcurrentHashMap<>();
    private final Flowable<Long> heartbeats;
    private volatile boolean running;

    @Autowired
    public PriceStreamService(QuoteBook quoteBook, PriceService priceService, MdsProperties properties,
                              MeterRegistry registry, @Qualifier("streamExecutor") ExecutorService executor) {
        this(quoteBook, priceService, properties, registry, Schedulers.computation(), Schedulers.from(executor));
    }

    /** With explicit schedulers, so tests can drive time ({@code TestScheduler}) and delivery. */
    public PriceStreamService(QuoteBook quoteBook, PriceService priceService, MdsProperties properties,
                              MeterRegistry registry, Scheduler throttleScheduler, Scheduler sendScheduler) {
        this.quoteBook = quoteBook;
        this.priceService = priceService;
        this.settings = properties.stream();
        this.registry = registry;
        this.throttleScheduler = throttleScheduler;
        this.sendScheduler = sendScheduler;
        this.heartbeats = ticker(settings.heartbeat(), throttleScheduler);
        for (String transport : new String[] {SSE, WEBSOCKET}) {
            AtomicInteger count = new AtomicInteger();
            subscribersByTransport.put(transport, count);
            Gauge.builder("mds.stream.subscribers", count, AtomicInteger::get)
                    .description("Open real-time price subscriptions")
                    .tag("transport", transport)
                    .register(registry);
            timersByTransport.put(transport, StreamTimers.register(registry, transport));
        }
    }

    // ---- lifecycle ----

    @Override
    public void start() {
        running = true;
    }

    /**
     * Closes every subscription, so each client reconnects through the load balancer to another
     * instance at once. By now the load balancer no longer routes to this one.
     */
    @Override
    public void stop() {
        running = false;
        int closed = shed(subscribers.get());
        LogEvent.STREAMS_CLOSED_FOR_SHUTDOWN.at(log).with("closed", closed).log();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * Resolves a client's requested rate. {@code rate} is a whole number of updates per second
     * ({@code 1}, {@code 2}, {@code 3}…) or {@code unlimited}; {@code throttle} is the older
     * minimum-interval form ({@code 500ms}). With neither, {@code mds.stream.default-throttle}.
     *
     * @throws IllegalArgumentException if both are given or the value is out of bounds
     */
    public StreamRate resolveRate(Optional<String> rate, Optional<Duration> throttle) {
        if (rate.isPresent() && throttle.isPresent()) {
            throw new IllegalArgumentException("give either rate or throttle, not both");
        }
        if (rate.isPresent()) {
            return parseRate(rate.get().trim());
        }
        Duration interval = throttle.orElse(settings.defaultThrottle());
        if (interval.compareTo(settings.minThrottle()) < 0 || interval.compareTo(settings.maxThrottle()) > 0) {
            throw new IllegalArgumentException("throttle must be between %dms and %dms"
                    .formatted(settings.minThrottle().toMillis(), settings.maxThrottle().toMillis()));
        }
        return StreamRate.every(interval);
    }

    private StreamRate parseRate(String value) {
        if (value.equalsIgnoreCase(StreamRate.UNLIMITED)) {
            if (!settings.allowUnthrottled()) {
                throw new IllegalArgumentException("rate=unlimited is disabled on this server; use 1 to " + maxRate());
            }
            return StreamRate.unthrottled();
        }
        int perSecond;
        try {
            perSecond = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "rate must be a whole number of updates per second (1 to %d) or '%s', was '%s'"
                            .formatted(maxRate(), StreamRate.UNLIMITED, value));
        }
        if (perSecond < 1 || perSecond > maxRate()) {
            throw new IllegalArgumentException("rate must be between 1 and %d updates per second, or '%s'"
                    .formatted(maxRate(), StreamRate.UNLIMITED));
        }
        return StreamRate.perSecond(perSecond);
    }

    /** Highest whole rate allowed: one update per {@code mds.stream.min-throttle}. */
    int maxRate() {
        return (int) Math.max(1, Duration.ofSeconds(1).toNanos() / settings.minThrottle().toNanos());
    }

    /**
     * Starts a subscription that calls {@code onPrice} with each aggregate (on a virtual thread; a
     * slow callback holds back only its own subscription). Errors thrown by {@code onPrice}
     * end the subscription and are passed to {@code onError}. {@code onShed} disconnects the client
     * when the subscription is {@linkplain #shed shed} to balance load, so it reconnects elsewhere.
     *
     * @throws PairNotSubscribedException if the pair isn't streamed
     * @throws StreamCapacityException    if {@code mds.stream.max-subscribers} is reached
     */
    public StreamSubscription open(MarketPair pair, StreamRate rate, String transport,
                                   PriceListener onPrice, Consumer<Throwable> onError, Runnable onShed) {
        return start(pair, rate, transport, onPrice, onError, onShed);
    }

    private Subscription start(MarketPair pair, StreamRate rate, String transport,
                               PriceListener onPrice, Consumer<Throwable> onError, Runnable onShed) {
        if (!priceService.pairs().contains(pair)) {
            throw new PairNotSubscribedException(pair, priceService.pairs());
        }
        AtomicInteger transportCount = reserveSlot(transport);
        Counter events = eventsCounter(pair, transport);
        StreamTimers timers = timersByTransport.get(transport);
        Subscription subscription = new Subscription(pair, rate, transport, transportCount, onShed);
        openByAge.put(subscription.id, subscription);
        subscription.disposable.add(releases(pair, rate).subscribe(
                release -> deliver(pair, release, onPrice, events, timers),
                error -> {
                    subscription.close();
                    onError.accept(error);
                }));
        log.debug("{} subscription for {} opened at {}", transport, pair, rate);
        return subscription;
    }

    /**
     * Calls {@code beat} every {@code mds.stream.heartbeat} on a send thread, until disposed; never
     * while the heartbeat is off. Ticks missed while {@code beat} is busy are dropped. An exception
     * from {@code beat} stops it and goes to {@code onError}.
     */
    public Disposable heartbeat(Heartbeat beat, Consumer<Throwable> onError) {
        return heartbeats
                .onBackpressureDrop()
                .observeOn(sendScheduler, false, 1)
                .subscribe(tick -> beat.send(), onError::accept);
    }

    /** Subscriptions open on this instance. */
    public int subscribers() {
        return subscribers.get();
    }

    /**
     * Closes up to {@code count} subscriptions, newest first so long-running clients are disturbed
     * least, and disconnects their clients so they reconnect, through the load balancer, to another
     * instance. A WebSocket carries several subscriptions and closing it ends all of them, so this
     * stops once {@code count} have ended. Returns how many ended.
     */
    public int shed(int count) {
        int before = subscribers.get();
        for (Subscription subscription : openByAge.descendingMap().values()) {
            if (before - subscribers.get() >= count) {
                break;
            }
            subscription.shed();
        }
        return before - subscribers.get();
    }

    /**
     * Takes one of the {@code mds.stream.max-subscribers} slots and counts it for the transport.
     * Returns the transport's count, which {@link Subscription#close} decrements.
     */
    private AtomicInteger reserveSlot(String transport) {
        AtomicInteger transportCount = subscribersByTransport.get(transport);
        if (transportCount == null) {
            throw new IllegalArgumentException("unknown transport " + transport);
        }
        if (subscribers.incrementAndGet() > settings.maxSubscribers()) {
            subscribers.decrementAndGet();
            throw new StreamCapacityException(settings.maxSubscribers());
        }
        transportCount.incrementAndGet();
        return transportCount;
    }

    private Counter eventsCounter(MarketPair pair, String transport) {
        return Counter.builder("mds.stream.events")
                .description("Price events sent to real-time subscribers")
                .tag("pair", pair.symbol())
                .tag("transport", transport)
                .register(registry);
    }

    /**
     * Opens an SSE subscription for the pair. Each event is named {@code price} and carries an
     * {@link AggregatedPrice} as JSON.
     */
    public SseEmitter subscribe(MarketPair pair, StreamRate rate) {
        SseEmitter emitter = new SseEmitter(settings.timeout().toMillis());
        AtomicLong sequence = new AtomicLong();
        Subscription subscription = start(pair, rate, SSE,
                price -> send(emitter, sequence.incrementAndGet(), price),
                emitter::completeWithError,
                () -> reconnectElsewhere(emitter));
        // an SSE comment line, ": heartbeat", which EventSource ignores
        subscription.disposable.add(heartbeat(() -> emitter.send(SseEmitter.event().comment("heartbeat")),
                emitter::completeWithError));
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(e -> subscription.close());
        return emitter;
    }

    /** Aggregates for the pair at the given rate, starting with the current one. */
    Flowable<AggregatedPrice> updates(MarketPair pair, StreamRate rate) {
        // skipped while no exchange has a fresh quote
        return releases(pair, rate).concatMapMaybe(release -> Maybe.fromOptional(priceService.current(pair)));
    }

    /**
     * Each time the subscriber is due an event, on a send thread: the current state first, then at
     * the subscriber's rate. Updates pass through as they are, so the only allocation is one
     * {@link Release} per event sent.
     */
    private Flowable<Release> releases(MarketPair pair, StreamRate rate) {
        Observable<QuoteUpdate> changes = quoteBook.updates()
                .filter(update -> update.pair().equals(pair))
                .startWithItem(new QuoteUpdate(pair, null, Release.NOT_AN_UPDATE));
        if (rate.throttled()) {
            changes = changes.throttleLatest(rate.minInterval().toNanos(), TimeUnit.NANOSECONDS,
                    throttleScheduler, true);
        }
        return changes
                .map(update -> new Release(update.receivedNanos(), System.nanoTime()))
                .toFlowable(BackpressureStrategy.LATEST)
                .observeOn(sendScheduler, false, 1);
    }

    /** Sends the pair's current aggregate, if there is one, and times how long it took to get out. */
    private void deliver(MarketPair pair, Release release, PriceListener onPrice, Counter events,
                         StreamTimers timers) throws Exception {
        Optional<AggregatedPrice> price = priceService.current(pair);
        if (price.isEmpty()) {
            return; // no exchange has a fresh quote
        }
        onPrice.accept(price.get());
        events.increment();
        timers.record(release, System.nanoTime());
    }

    /** One shared tick for every heartbeat; never ticks when {@code interval} is zero. */
    private static Flowable<Long> ticker(Duration interval, Scheduler scheduler) {
        if (interval.isZero()) {
            return Flowable.never();
        }
        return Flowable.interval(interval.toNanos(), interval.toNanos(), TimeUnit.NANOSECONDS, scheduler)
                .onBackpressureDrop()
                .share();
    }

    /** Ends the stream with a {@code reconnect} event; EventSource clients reconnect after a second. */
    private static void reconnectElsewhere(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("reconnect").reconnectTime(1000).data("rebalancing"));
        } catch (IOException e) {
            // already gone
        }
        emitter.complete();
    }

    private static void send(SseEmitter emitter, long id, AggregatedPrice price) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(id))
                .name("price")
                .data(price, MediaType.APPLICATION_JSON));
    }

    /**
     * An event the subscriber's rate let go: when mds received the newest update it reflects
     * ({@link #NOT_AN_UPDATE} for the current state sent on subscribing), and when it was let go.
     * Both are {@link System#nanoTime()}: durations, not points in time.
     */
    private record Release(long receivedNanos, long releasedNanos) {

        static final long NOT_AN_UPDATE = Long.MIN_VALUE;
    }

    /** The latency and queue timers of one transport, registered once. */
    private record StreamTimers(Timer latency, Timer queue) {

        static StreamTimers register(MeterRegistry registry, String transport) {
            return new StreamTimers(
                    Timer.builder("mds.stream.latency")
                            .description("From receiving an exchange update to handing it to a subscriber")
                            .tag("transport", transport)
                            .register(registry),
                    Timer.builder("mds.stream.queue")
                            .description("From a subscriber's rate letting an event go to handing it over")
                            .tag("transport", transport)
                            .register(registry));
        }

        void record(Release release, long sentNanos) {
            queue.record(sentNanos - release.releasedNanos(), TimeUnit.NANOSECONDS);
            if (release.receivedNanos() != Release.NOT_AN_UPDATE) {
                latency.record(sentNanos - release.receivedNanos(), TimeUnit.NANOSECONDS);
            }
        }
    }

    /** An open subscription; closing it stops its updates and frees its slot, once. */
    private final class Subscription implements StreamSubscription {

        private final long id = opened.incrementAndGet();
        private final MarketPair pair;
        private final StreamRate rate;
        private final String transport;
        private final AtomicInteger transportCount;
        private final Runnable onShed;
        private final CompositeDisposable disposable = new CompositeDisposable();
        private final AtomicBoolean closed = new AtomicBoolean();

        Subscription(MarketPair pair, StreamRate rate, String transport, AtomicInteger transportCount,
                     Runnable onShed) {
            this.pair = pair;
            this.rate = rate;
            this.transport = transport;
            this.transportCount = transportCount;
            this.onShed = onShed;
        }

        /** Disconnects the client, then closes, in case disconnecting didn't. */
        void shed() {
            try {
                onShed.run();
            } finally {
                close();
            }
        }

        @Override
        public MarketPair pair() {
            return pair;
        }

        @Override
        public StreamRate rate() {
            return rate;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                openByAge.remove(id);
                disposable.dispose();
                subscribers.decrementAndGet();
                transportCount.decrementAndGet();
                log.debug("{} subscription for {} closed", transport, pair);
            }
        }
    }

    /** Sends one heartbeat; may throw when the client has gone. */
    @FunctionalInterface
    public interface Heartbeat {
        void send() throws Exception;
    }

    /** Receives each aggregate; may throw to end the subscription (e.g. the client went away). */
    @FunctionalInterface
    public interface PriceListener {
        void accept(AggregatedPrice price) throws Exception;
    }
}
