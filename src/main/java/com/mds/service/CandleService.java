package com.mds.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.util.stream.Collectors.toSet;

import com.mds.config.MdsProperties;
import com.mds.logging.LogEvent;
import com.mds.math.Decimal;
import com.mds.model.Candle;
import com.mds.model.CandlePeriod;
import com.mds.model.MarketPair;
import com.mds.model.QuoteUpdate;
import com.mds.persistence.PriceRepository;
import io.reactivex.rxjava3.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Builds daily, weekly, monthly and yearly OHLC candles from every live quote update and keeps
 * them in InfluxDB.
 *
 * <p>For each pair there is one candle set per exchange (from its last price) and one for the
 * {@value #INDEX} source (the median of the exchanges' fresh last prices, see
 * {@link PriceService#aggregate}). Candles are built in memory, so the high and low see every
 * update rather than the archive's snapshots.
 *
 * <p>Timeliness:
 * <ul>
 *   <li>every {@code mds.ohlc.flush-interval} all in-progress candles are written, and candles
 *       whose period has ended are closed and written with {@code complete=true};</li>
 *   <li>an extra flush runs at local midnight in {@code mds.ohlc.zone}, the boundary every period
 *       shares, so completed candles land within a second of the period end;</li>
 *   <li>a completed candle whose write fails stays queued and is retried on the next flush;</li>
 *   <li>on startup the latest candle of every series is reloaded from InfluxDB: one for the
 *       current period continues (keeping its open, high and low), and any periods missed while the
 *       service was down are filled in (see below); on shutdown a final flush runs.</li>
 * </ul>
 *
 * <p>Continuity: every period gets a candle, so charts have no holes. When a period ends, the next
 * period's candle is seeded straight away as a flat placeholder at the last close
 * ({@code open = high = low = close}, {@code updates = 0}). The first real price in that period
 * replaces the placeholder, so open is still the first traded price; if none arrives the
 * placeholder is what's stored. Periods missed during downtime are back-filled the same way from
 * the last stored close. Only active sources are carried forward: the index and exchanges listed
 * in {@code mds.exchanges}, for pairs in {@code mds.pairs}.
 */
@Slf4j
@Service
public class CandleService implements SmartLifecycle {

    public static final String INDEX = "index";

    private record Key(String source, MarketPair pair, CandlePeriod period) {
    }

    private final QuoteBook quoteBook;
    private final PriceService priceService;
    private final PriceRepository repository;
    private final ZoneId zone;
    private final Clock clock;
    private final Set<String> exchanges;
    private final Set<MarketPair> pairs;
    private final Map<Key, Candle> open = new ConcurrentHashMap<>();
    private final Queue<Candle> completed = new ConcurrentLinkedQueue<>();
    private volatile Disposable subscription;

    public CandleService(QuoteBook quoteBook, PriceService priceService, PriceRepository repository,
                         MdsProperties properties, Clock clock) {
        this.quoteBook = quoteBook;
        this.priceService = priceService;
        this.repository = repository;
        this.zone = properties.ohlc().zone();
        this.clock = clock;
        this.exchanges = properties.exchanges().stream().map(e -> e.toLowerCase(Locale.ROOT)).collect(toSet());
        this.pairs = properties.pairs().stream().map(MarketPair::parse).collect(toSet());
    }

    // ---- lifecycle ----

    @Override
    public void start() {
        recover();
        subscription = quoteBook.updates().subscribe(this::onUpdate,
                e -> LogEvent.CANDLE_UPDATES_STOPPED.at(log).cause(e).log());
        LogEvent.CANDLES_STARTED.at(log).with("zone", zone).log();
    }

    @Override
    public void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
        flush();
    }

    @Override
    public boolean isRunning() {
        return subscription != null;
    }

    /** Starts before and stops after {@link com.mds.exchange.MarketStreamManager}. */
    @Override
    public int getPhase() {
        return DEFAULT_PHASE - 1;
    }

    // ---- updates ----

    void onUpdate(QuoteUpdate update) {
        Instant at = update.quote().timestamp();
        if (update.quote().last() != null) {
            applyAll(update.quote().exchange(), update.pair(), update.quote().last(), at);
        }
        try {
            applyAll(INDEX, update.pair(), priceService.aggregate(update.pair()).median(), at);
        } catch (PriceUnavailableException | PairNotSubscribedException e) {
            // no fresh price to index yet
        }
    }

    private void applyAll(String source, MarketPair pair, Decimal price, Instant at) {
        for (CandlePeriod period : CandlePeriod.values()) {
            open.compute(new Key(source, pair, period), (key, current) -> {
                if (current == null) {
                    return Candle.open(pair.toString(), source, period, period.start(at, zone), price);
                }
                Candle rolled = rollForward(current, at, true);
                // an update stamped before the current period (clock skew) is ignored
                return at.isBefore(rolled.start()) ? rolled : rolled.with(price);
            });
        }
    }

    /**
     * Completes {@code candle} and every following period that has ended by {@code now}, filling
     * each skipped period with a flat candle at the last close. Returns the candle for the period
     * containing {@code now}, or null once the series ends because its source is no longer active.
     */
    private Candle rollForward(Candle candle, Instant now, boolean active) {
        Candle current = candle;
        Instant end = current.period().end(current.start(), zone);
        while (!now.isBefore(end)) {
            completed.add(current.completed());
            if (!active) {
                return null;
            }
            current = current.carriedForward(end);
            end = current.period().end(end, zone);
        }
        return current;
    }

    private boolean isActive(Key key) {
        return pairs.contains(key.pair()) && (key.source().equals(INDEX) || exchanges.contains(key.source()));
    }

    // ---- persistence ----

    @Scheduled(fixedDelayString = "${mds.ohlc.flush-interval:10s}", initialDelayString = "${mds.ohlc.flush-interval:10s}")
    public void scheduledFlush() {
        flush();
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "${mds.ohlc.zone:UTC}")
    public void periodBoundaryFlush() {
        log.debug("Period boundary flush");
        flush();
    }

    /** Closes ended periods and writes completed plus in-progress candles. */
    synchronized void flush() {
        Instant now = clock.instant();
        for (Key key : open.keySet()) {
            open.computeIfPresent(key, (k, candle) -> rollForward(candle, now, isActive(k)));
        }

        List<Candle> finished = new ArrayList<>();
        for (Candle candle; (candle = completed.poll()) != null; ) {
            finished.add(candle);
        }
        List<Candle> batch = new ArrayList<>(finished);
        batch.addAll(open.values());
        try {
            repository.saveCandles(batch);
            if (!finished.isEmpty()) {
                LogEvent.CANDLES_COMPLETED.at(log).with("count", finished.size()).log();
            }
        } catch (RuntimeException e) {
            completed.addAll(finished);
            LogEvent.CANDLE_WRITE_FAILED.at(log).with("count", batch.size()).with("reason", e.getMessage()).log();
        }
    }

    /**
     * Reloads the latest stored candle of every series and rolls it forward to now: a candle for
     * the current period continues, ended ones are completed, and periods missed while the service
     * was down are filled with flat candles at the last close.
     */
    private void recover() {
        Instant now = clock.instant();
        Instant since = CandlePeriod.YEAR.start(now, zone).atZone(zone).minusYears(1).toInstant();
        List<Candle> stored;
        try {
            stored = repository.findLatestCandles(since);
        } catch (RuntimeException e) {
            LogEvent.CANDLE_RELOAD_FAILED.at(log).cause(e).log();
            return;
        }
        int resumed = 0;
        int before = completed.size();
        for (Candle candle : stored) {
            Key key = new Key(candle.source(), MarketPair.parse(candle.pair()), candle.period());
            boolean active = isActive(key);
            Candle current;
            if (candle.complete()) {
                // already final; resume from the period after it
                current = active ? rollForward(candle.carriedForward(candle.period().end(candle.start(), zone)), now,
                        true) : null;
            } else {
                current = rollForward(candle, now, active);
            }
            if (current != null) {
                open.put(key, current);
                resumed++;
            }
        }
        LogEvent.CANDLES_RECOVERED.at(log).with("series", resumed)
                .with("backfill_count", completed.size() - before).log();
    }

    // ---- queries ----

    /** In-progress candles for the pair and source, shortest period first. */
    public List<Candle> current(MarketPair pair, String source) {
        return open.entrySet().stream()
                .filter(e -> e.getKey().pair().equals(pair) && e.getKey().source().equals(source))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(Candle::period))
                .toList();
    }

    public ZoneId zone() {
        return zone;
    }
}
