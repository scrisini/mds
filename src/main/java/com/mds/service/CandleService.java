package com.mds.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.mds.config.MdsProperties;
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
 *   <li>on startup incomplete candles are reloaded from InfluxDB: those for the current period
 *       continue (keeping their open, high and low), those whose period ended while the service
 *       was down are written as complete; on shutdown a final flush runs.</li>
 * </ul>
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
    }

    // ---- lifecycle ----

    @Override
    public void start() {
        recover();
        subscription = quoteBook.updates().subscribe(this::onUpdate,
                e -> log.error("Candle updates stopped", e));
        log.info("Building day/week/month/year candles (zone {}) for the index and each exchange", zone);
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
                if (current != null && !at.isBefore(period.end(current.start(), zone))) {
                    completed.add(current.completed());
                    current = null;
                }
                if (current == null) {
                    return Candle.open(pair.toString(), source, period, period.start(at, zone), price);
                }
                // an update stamped before the current period (clock skew) is ignored
                return at.isBefore(current.start()) ? current : current.with(price);
            });
        }
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
            open.computeIfPresent(key, (k, candle) -> {
                if (now.isBefore(k.period().end(candle.start(), zone))) {
                    return candle;
                }
                completed.add(candle.completed());
                return null;
            });
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
                log.info("Completed {} candles", finished.size());
            }
        } catch (RuntimeException e) {
            completed.addAll(finished);
            log.warn("Failed to write {} candles to InfluxDB, retrying next flush: {}", batch.size(), e.getMessage());
        }
    }

    /** Reloads incomplete candles, continuing the current periods and finalising past ones. */
    private void recover() {
        Instant now = clock.instant();
        Instant since = CandlePeriod.YEAR.start(now, zone).atZone(zone).minusYears(1).toInstant();
        List<Candle> stored;
        try {
            stored = repository.findIncompleteCandles(since);
        } catch (RuntimeException e) {
            log.warn("Could not reload candles from InfluxDB, starting fresh: {}", e.getMessage());
            return;
        }
        int resumed = 0;
        for (Candle candle : stored) {
            if (candle.start().equals(candle.period().start(now, zone))) {
                open.put(new Key(candle.source(), MarketPair.parse(candle.pair()), candle.period()), candle);
                resumed++;
            } else {
                completed.add(candle.completed());
            }
        }
        log.info("Recovered {} in-progress candles; {} from ended periods will be completed",
                resumed, stored.size() - resumed);
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
