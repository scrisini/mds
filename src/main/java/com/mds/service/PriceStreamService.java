package com.mds.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.mds.config.MdsProperties;
import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.model.QuoteUpdate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes live aggregates to Server-Sent Event subscribers, throttled per subscriber.
 *
 * <p>Throttling uses latest-value semantics: the first update is sent immediately, then at most
 * one per interval, always the most recent state. A client that can't keep up is sent the latest
 * aggregate when it catches up, never a backlog.
 *
 * <p>Metrics: {@code mds.stream.subscribers} gauge, {@code mds.stream.events} counter tagged
 * {@code pair}.
 */
@Slf4j
@Service
public class PriceStreamService {

    private final QuoteBook quoteBook;
    private final PriceService priceService;
    private final MdsProperties.Stream settings;
    private final MeterRegistry registry;
    private final Scheduler throttleScheduler;
    private final Scheduler sendScheduler;
    private final AtomicInteger subscribers = new AtomicInteger();

    @Autowired
    public PriceStreamService(QuoteBook quoteBook, PriceService priceService, MdsProperties properties,
                              MeterRegistry registry, @Qualifier("streamExecutor") ExecutorService executor) {
        this(quoteBook, priceService, properties, registry, Schedulers.computation(), Schedulers.from(executor));
    }

    PriceStreamService(QuoteBook quoteBook, PriceService priceService, MdsProperties properties,
                       MeterRegistry registry, Scheduler throttleScheduler, Scheduler sendScheduler) {
        this.quoteBook = quoteBook;
        this.priceService = priceService;
        this.settings = properties.stream();
        this.registry = registry;
        this.throttleScheduler = throttleScheduler;
        this.sendScheduler = sendScheduler;
        Gauge.builder("mds.stream.subscribers", subscribers, AtomicInteger::get)
                .description("Open SSE price subscriptions")
                .register(registry);
    }

    /**
     * Opens an SSE subscription for the pair. Each event is named {@code price} and carries an
     * {@link AggregatedPrice} as JSON.
     *
     * @param throttle minimum time between events; defaults to {@code mds.stream.default-throttle}
     */
    public SseEmitter subscribe(MarketPair pair, Optional<Duration> throttle) {
        if (!priceService.pairs().contains(pair)) {
            throw new PairNotSubscribedException(pair, priceService.pairs());
        }
        Duration interval = throttle.orElse(settings.defaultThrottle());
        if (interval.compareTo(settings.minThrottle()) < 0 || interval.compareTo(settings.maxThrottle()) > 0) {
            throw new IllegalArgumentException("throttle must be between %dms and %dms"
                    .formatted(settings.minThrottle().toMillis(), settings.maxThrottle().toMillis()));
        }
        if (subscribers.incrementAndGet() > settings.maxSubscribers()) {
            subscribers.decrementAndGet();
            throw new StreamCapacityException(settings.maxSubscribers());
        }

        SseEmitter emitter = new SseEmitter(settings.timeout().toMillis());
        CompositeDisposable subscription = new CompositeDisposable();
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                subscription.dispose();
                subscribers.decrementAndGet();
                log.debug("SSE subscription for {} closed", pair);
            }
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(e -> release.run());

        Counter events = Counter.builder("mds.stream.events")
                .description("Price events sent to SSE subscribers")
                .tag("pair", pair.symbol())
                .register(registry);
        AtomicLong sequence = new AtomicLong();
        subscription.add(updates(pair, interval).subscribe(
                price -> {
                    send(emitter, sequence.incrementAndGet(), price);
                    events.increment();
                },
                emitter::completeWithError));

        log.debug("SSE subscription for {} opened, throttle {}", pair, interval);
        return emitter;
    }

    /** Throttled aggregates for the pair, starting with the current one. */
    Flowable<AggregatedPrice> updates(MarketPair pair, Duration throttle) {
        return quoteBook.updates()
                .map(QuoteUpdate::pair)
                .filter(pair::equals)
                .startWithItem(pair)
                .throttleLatest(throttle.toMillis(), TimeUnit.MILLISECONDS, throttleScheduler, true)
                .toFlowable(BackpressureStrategy.LATEST)
                .observeOn(sendScheduler, false, 1)
                .concatMapMaybe(p -> Maybe.fromCallable(() -> currentAggregate(p)));
    }

    /** Null (skipped) while no exchange has a fresh quote. */
    private AggregatedPrice currentAggregate(MarketPair pair) {
        try {
            return priceService.aggregate(pair);
        } catch (PriceUnavailableException e) {
            return null;
        }
    }

    private static void send(SseEmitter emitter, long id, AggregatedPrice price) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(id))
                .name("price")
                .data(price, MediaType.APPLICATION_JSON));
    }
}
