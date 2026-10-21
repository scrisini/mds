package com.mds.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mds.model.MarketPair;
import com.mds.model.PriceQuote;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.springframework.stereotype.Component;

/**
 * Latest quote per exchange and pair, updated from WebSocket callbacks. Thread-safe:
 * updates arrive concurrently on each exchange's I/O thread.
 */
@Component
public class QuoteBook {

    private record Key(String exchange, MarketPair pair) {
    }

    private final Clock clock;
    private final Map<Key, PriceQuote> quotes = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastUpdate = new ConcurrentHashMap<>();

    public QuoteBook(Clock clock) {
        this.clock = clock;
    }

    public void onTicker(String exchange, MarketPair pair, Ticker ticker) {
        Instant now = clock.instant();
        PriceQuote update = new PriceQuote(exchange, pair.toString(), ticker.getLast(), ticker.getBid(),
                ticker.getAsk(), ticker.getHigh(), ticker.getLow(), ticker.getVolume(), ticker.getQuoteVolume(), now);
        quotes.merge(new Key(exchange, pair), update, PriceQuote::mergedWith);
        lastUpdate.put(exchange, now);
    }

    public void onTrade(String exchange, MarketPair pair, BigDecimal price) {
        Instant now = clock.instant();
        PriceQuote update = new PriceQuote(exchange, pair.toString(), price, null, null, null, null, null, null, now);
        quotes.merge(new Key(exchange, pair), update, PriceQuote::mergedWith);
        lastUpdate.put(exchange, now);
    }

    public Optional<PriceQuote> latest(String exchange, MarketPair pair) {
        return Optional.ofNullable(quotes.get(new Key(exchange, pair)));
    }

    public Optional<Instant> lastUpdate(String exchange) {
        return Optional.ofNullable(lastUpdate.get(exchange));
    }
}
