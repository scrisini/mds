package com.mds.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static java.util.stream.Collectors.toSet;

import com.hazelcast.map.IMap;
import com.mds.cluster.Cluster;
import com.mds.config.MdsProperties;
import com.mds.exchange.MarketStreamManager;
import com.mds.logging.LogEvent;
import com.mds.math.Decimal;
import com.mds.model.Candle;
import com.mds.model.CandlePeriod;
import com.mds.model.MarketPair;
import com.mds.model.QuoteUpdate;
import com.mds.persistence.PriceRepository;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>Clustering: in-progress candles live in the cluster map {@value Cluster#CANDLES}, with a backup
 * on another member. Each pair is owned by one member ({@link Cluster#owns}); only the owner applies
 * its prices, closes its periods and writes its candles, so nothing is counted or written twice. When
 * the owner leaves, the member holding the backups takes over and continues the same candles.
 * Completed candles wait in {@value Cluster#COMPLETED_CANDLES} until they are written, so a failed
 * write is retried by whichever member owns the pair next. Updates are applied on a thread of their
 * own, never on an exchange's I/O thread.
 *
 * <p>Timeliness:
 * <ul>
 *   <li>every {@code mds.ohlc.flush-interval} all in-progress candles are written, and candles
 *       whose period has ended are closed and written with {@code complete=true};</li>
 *   <li>an extra flush runs at local midnight in {@code mds.ohlc.zone}, the boundary every period
 *       shares, so completed candles land within a second of the period end;</li>
 *   <li>a completed candle whose write fails stays queued and is retried on the next flush;</li>
 *   <li>a member that owns a pair whose candles aren't in the cluster (the cluster just started, or
 *       lost them in a split or a crash of two     members) first reloads the pair's latest stored candles
 *       from InfluxDB: one for the current period continues (keeping its open, high and low), and any
 *       periods missed meanwhile are filled in (see below). A marker in {@value Cluster#LOADED_PAIRS},
 *       which lives and is lost with the pair's candles, records that they are loaded. On shutdown a
 *       final flush runs.</li>
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

    private final QuoteBook quoteBook;
    private final PriceService priceService;
    private final PriceRepository repository;
    private final Cluster cluster;
    private final IMap<CandleKey, Candle> open;
    private final IMap<CompletedCandleKey, Candle> completed;
    private final IMap<String, Boolean> loaded;
    private final ZoneId zone;
    private final Clock clock;
    private final Scheduler updateScheduler;
    private final Set<String> exchanges;
    private final Set<MarketPair> pairs;
    private volatile Disposable subscription;
    private volatile boolean updatesFailing;

    @Autowired
    public CandleService(QuoteBook quoteBook, PriceService priceService, PriceRepository repository, Cluster cluster,
                         MdsProperties properties, Clock clock) {
        this(quoteBook, priceService, repository, cluster, properties, clock, Schedulers.newThread());
    }

    /** With an explicit scheduler for candle updates, so tests can apply them synchronously. */
    public CandleService(QuoteBook quoteBook, PriceService priceService, PriceRepository repository, Cluster cluster,
                         MdsProperties properties, Clock clock, Scheduler updateScheduler) {
        this.quoteBook = quoteBook;
        this.priceService = priceService;
        this.repository = repository;
        this.cluster = cluster;
        this.open = cluster.map(Cluster.CANDLES);
        this.completed = cluster.map(Cluster.COMPLETED_CANDLES);
        this.loaded = cluster.map(Cluster.LOADED_PAIRS);
        this.zone = properties.ohlc().zone();
        this.clock = clock;
        this.updateScheduler = updateScheduler;
        this.exchanges = properties.exchanges().stream().map(e -> e.toLowerCase(Locale.ROOT)).collect(toSet());
        this.pairs = properties.pairs().stream().map(MarketPair::parse).collect(toSet());
    }

    // ---- lifecycle ----

    @Override
    public void start() {
        if (cluster.isOldestMember()) {
            closeRemovedPairs();
        }
        loadOwnedPairs();
        subscription = quoteBook.updates().observeOn(updateScheduler).subscribe(this::onUpdate,
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

    /** Starts before and stops after {@link MarketStreamManager}. */
    @Override
    public int getPhase() {
        return MarketStreamManager.PHASE - 1;
    }

    // ---- updates ----

    /** Applies the update to this member's pairs; a cluster failure skips it rather than stopping candles. */
    void onUpdate(QuoteUpdate update) {
        MarketPair pair = update.pair();
        if (!cluster.owns(pair)) {
            return;
        }
        try {
            ensureLoaded(pair);
            apply(update);
            updatesFailing = false;
        } catch (RuntimeException e) {
            if (!updatesFailing) {
                updatesFailing = true;
                LogEvent.CANDLE_UPDATE_FAILED.at(log).with("pair", pair).with("reason", e.getMessage()).log();
            }
        }
    }

    private void apply(QuoteUpdate update) {
        MarketPair pair = update.pair();
        Instant at = update.quote().timestamp();
        if (update.quote().last() != null) {
            applyAll(update.quote().exchange(), pair, update.quote().last(), at);
        }
        // the index covers configured pairs only, and has no candle while no exchange has a fresh price
        if (pairs.contains(pair)) {
            priceService.current(pair).ifPresent(index -> applyAll(INDEX, pair, index.median(), at));
        }
    }

    private void applyAll(String source, MarketPair pair, Decimal price, Instant at) {
        ApplyPrice update = new ApplyPrice(price, at, zone.getId());
        for (CandlePeriod period : CandlePeriod.values()) {
            queueCompleted(open.executeOnKey(new CandleKey(source, pair, period), update));
        }
    }

    /** Queues candles completed by an entry processor until a flush writes them. */
    private void queueCompleted(List<Candle> done) {
        if (done == null) {
            return;
        }
        for (Candle candle : done) {
            completed.set(new CompletedCandleKey(keyOf(candle), candle.start()), candle);
        }
    }

    private boolean isActive(CandleKey key) {
        return pairs.contains(key.pair()) && (key.source().equals(INDEX) || exchanges.contains(key.source()));
    }

    private static CandleKey keyOf(Candle candle) {
        return new CandleKey(candle.source(), MarketPair.parse(candle.pair()), candle.period());
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

    /** Closes ended periods and writes completed plus in-progress candles, for this member's pairs. */
    synchronized void flush() {
        loadOwnedPairs(); // e.g. pairs taken over from a member that left, which may see no prices yet
        closeEndedPeriods(clock.instant());
        write();
    }

    private void closeEndedPeriods(Instant now) {
        for (CandleKey key : open.localKeySet()) {
            queueCompleted(open.executeOnKey(key, new RollForward(now, isActive(key), zone.getId())));
        }
    }

    /** Writes this member's completed and in-progress candles; completed ones stay queued until written. */
    private void write() {
        Map<CompletedCandleKey, Candle> finished = completed.getAll(completed.localKeySet());
        List<Candle> batch = new ArrayList<>(finished.values());
        batch.addAll(open.getAll(open.localKeySet()).values());
        batch.sort(Comparator.comparing(Candle::start));
        try {
            repository.saveCandles(batch);
            finished.keySet().forEach(completed::delete);
            if (!finished.isEmpty()) {
                LogEvent.CANDLES_COMPLETED.at(log).with("count", finished.size()).log();
            }
        } catch (RuntimeException e) {
            LogEvent.CANDLE_WRITE_FAILED.at(log).with("count", batch.size()).with("reason", e.getMessage()).log();
        }
    }

    private void loadOwnedPairs() {
        for (MarketPair pair : pairs) {
            if (cluster.owns(pair)) {
                ensureLoaded(pair);
            }
        }
    }

    /** Reloads the pair's candles from InfluxDB unless the cluster already holds them. */
    private void ensureLoaded(MarketPair pair) {
        if (!loaded.containsKey(pair.toString())) {
            load(pair);
            loaded.set(pair.toString(), true);
        }
    }

    /**
     * Reloads the latest stored candle of each of the pair's series and rolls it forward to now: a
     * candle for the current period continues, ended ones are completed, and periods missed meanwhile
     * are filled with flat candles at the last close. If InfluxDB is unavailable the pair starts fresh.
     */
    private void load(MarketPair pair) {
        Instant now = clock.instant();
        List<Candle> stored;
        try {
            stored = repository.findLatestCandles(pair, reloadSince(now));
        } catch (RuntimeException e) {
            LogEvent.CANDLE_RELOAD_FAILED.at(log).with("pair", pair).cause(e).log();
            return;
        }
        int resumed = 0;
        List<Candle> backfill = new ArrayList<>();
        for (Candle candle : stored) {
            CandleKey key = keyOf(candle);
            Candle current = resume(candle, now, isActive(key), backfill::add);
            if (current != null && open.putIfAbsent(key, current) == null) {
                resumed++;
            }
        }
        queueCompleted(backfill);
        LogEvent.CANDLES_RECOVERED.at(log).with("pair", pair).with("series", resumed)
                .with("backfill_count", backfill.size()).log();
    }

    /**
     * Completes the stored candles of pairs no longer in {@code mds.pairs}, which no member owns as a
     * configured pair. Runs once, on the member that starts the cluster; a candle whose period hasn't
     * ended yet is completed by its partition's owner when it does.
     */
    private void closeRemovedPairs() {
        Instant now = clock.instant();
        List<Candle> removed;
        try {
            removed = repository.findLatestCandles(reloadSince(now)).stream()
                    .filter(c -> !c.complete() && !pairs.contains(MarketPair.parse(c.pair())))
                    .toList();
        } catch (RuntimeException e) {
            LogEvent.CANDLE_RELOAD_FAILED.at(log).cause(e).log();
            return;
        }
        List<Candle> done = new ArrayList<>();
        for (Candle candle : removed) {
            Candle current = candle.rolledForward(now, zone, false, done::add);
            if (current != null) {
                open.putIfAbsent(keyOf(candle), current);
            }
        }
        queueCompleted(done);
    }

    private Instant reloadSince(Instant now) {
        return CandlePeriod.YEAR.start(now, zone).atZone(zone).minusYears(1).toInstant();
    }

    /**
     * The candle that continues a stored series at {@code now}, or null if the series has ended
     * because its source is no longer active.
     */
    private Candle resume(Candle stored, Instant now, boolean active, Consumer<Candle> done) {
        if (!stored.complete()) {
            return stored.rolledForward(now, zone, active, done);
        }
        if (!active) {
            return null;
        }
        // already final; resume from the period after it
        return stored.carriedForward(stored.period().end(stored.start(), zone)).rolledForward(now, zone, true, done);
    }

    // ---- queries ----

    /** In-progress candles for the pair and source, shortest period first; from any member. */
    public List<Candle> current(MarketPair pair, String source) {
        Set<CandleKey> keys = new HashSet<>();
        for (CandlePeriod period : CandlePeriod.values()) {
            keys.add(new CandleKey(source, pair, period));
        }
        return open.getAll(keys).values().stream()
                .sorted(Comparator.comparing(Candle::period))
                .toList();
    }

    public ZoneId zone() {
        return zone;
    }
}
