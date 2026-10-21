package com.mds.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mds.math.Decimal;
import com.mds.model.MarketPair;
import com.mds.model.PriceQuote;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.Subject;
import lombok.extern.slf4j.Slf4j;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.springframework.stereotype.Component;

/**
 * Latest quote per exchange and pair, updated from WebSocket callbacks. Thread-safe:
 * updates arrive concurrently on each exchange's I/O thread.
 *
 * <p>This is where XChange's {@code BigDecimal}s are converted to {@link Decimal}. A field
 * outside the configured decimal range is dropped (logged once per exchange and field) rather
 * than discarding the whole update.
 */
@Slf4j
@Component
public class QuoteBook {

    private record Key(String exchange, MarketPair pair) {
    }

    private final Clock clock;
    private final Map<Key, PriceQuote> quotes = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastUpdate = new ConcurrentHashMap<>();
    private final Set<String> reportedOverflows = ConcurrentHashMap.newKeySet();
    // serialized: onNext is called concurrently from several exchanges' threads
    private final Subject<MarketPair> updates = PublishSubject.<MarketPair>create().toSerialized();

    public QuoteBook(Clock clock) {
        this.clock = clock;
    }

    public void onTicker(String exchange, MarketPair pair, Ticker ticker) {
        PriceQuote update = new PriceQuote(exchange, pair.toString(),
                convert(exchange, "last", ticker.getLast()),
                convert(exchange, "bid", ticker.getBid()),
                convert(exchange, "ask", ticker.getAsk()),
                convert(exchange, "high", ticker.getHigh()),
                convert(exchange, "low", ticker.getLow()),
                convert(exchange, "volume", ticker.getVolume()),
                convert(exchange, "quoteVolume", ticker.getQuoteVolume()),
                clock.instant());
        apply(update, pair);
    }

    public void onTrade(String exchange, MarketPair pair, BigDecimal price) {
        PriceQuote update = new PriceQuote(exchange, pair.toString(), convert(exchange, "last", price),
                null, null, null, null, null, null, clock.instant());
        apply(update, pair);
    }

    public Optional<PriceQuote> latest(String exchange, MarketPair pair) {
        return Optional.ofNullable(quotes.get(new Key(exchange, pair)));
    }

    public Optional<Instant> lastUpdate(String exchange) {
        return Optional.ofNullable(lastUpdate.get(exchange));
    }

    /** Emits the pair each time any exchange's quote for it changes. Never terminates. */
    public Observable<MarketPair> updates() {
        return updates.hide();
    }

    private void apply(PriceQuote update, MarketPair pair) {
        quotes.merge(new Key(update.exchange(), pair), update, PriceQuote::mergedWith);
        lastUpdate.put(update.exchange(), update.timestamp());
        updates.onNext(pair);
    }

    private Decimal convert(String exchange, String field, BigDecimal value) {
        try {
            return Decimal.ofNullable(value);
        } catch (ArithmeticException e) {
            if (reportedOverflows.add(exchange + "." + field)) {
                log.warn("{} {}={} exceeds the decimal range at scale {} (max {}); dropping this field",
                        exchange, field, value, Decimal.scale(), Decimal.max());
            }
            return null;
        }
    }
}
