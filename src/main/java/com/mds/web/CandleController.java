package com.mds.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.mds.exchange.MarketStreamManager;
import com.mds.model.Candle;
import com.mds.model.CandlePeriod;
import com.mds.model.MarketPair;
import com.mds.persistence.PriceRepository;
import com.mds.service.CandleService;
import com.mds.service.PairNotSubscribedException;
import com.mds.service.PriceService;
import com.mds.service.UnknownExchangeException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OHLC candles. {@code source} is {@code index} (cross-exchange median, the default) or an
 * exchange id.
 */
@RestController
@RequestMapping("/api/v1/candles/{pair}")
public class CandleController {

    private static final int MAX_LIMIT = 10_000;

    private final CandleService candles;
    private final PriceRepository repository;
    private final PriceService priceService;
    private final MarketStreamManager streams;
    private final Clock clock;

    public CandleController(CandleService candles, PriceRepository repository, PriceService priceService,
                            MarketStreamManager streams, Clock clock) {
        this.candles = candles;
        this.repository = repository;
        this.priceService = priceService;
        this.streams = streams;
        this.clock = clock;
    }

    /**
     * Stored candles, oldest first, e.g. {@code /api/v1/candles/BTC-USDT?period=day}. The
     * current period's candle is included with {@code complete=false}. {@code from} defaults to
     * 30 days, 26 weeks, 24 months or 10 years back depending on {@code period}.
     */
    @GetMapping
    public List<Candle> history(
            @PathVariable String pair,
            @RequestParam(defaultValue = "day") String period,
            @RequestParam(defaultValue = CandleService.INDEX) String source,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> to,
            @RequestParam(defaultValue = "1000") int limit) {
        MarketPair marketPair = subscribed(pair);
        CandlePeriod candlePeriod = CandlePeriod.parse(period);
        String sourceId = checkedSource(source);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        Instant end = to.orElseGet(clock::instant);
        Instant start = from.orElseGet(() -> defaultFrom(candlePeriod, end));
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }
        return repository.findCandles(marketPair, sourceId, candlePeriod, start, end, limit);
    }

    /** Live in-progress candles for every period, from memory. */
    @GetMapping("/current")
    public List<Candle> current(@PathVariable String pair,
                                @RequestParam(defaultValue = CandleService.INDEX) String source) {
        return candles.current(subscribed(pair), checkedSource(source));
    }

    /** Start of the period containing the instant one default lookback (e.g. 30 days) before {@code end}. */
    private Instant defaultFrom(CandlePeriod period, Instant end) {
        Instant lookback = end.atZone(candles.zone()).minus(period.defaultLookback()).toInstant();
        return period.start(lookback, candles.zone());
    }

    private MarketPair subscribed(String pair) {
        MarketPair marketPair = MarketPair.parse(pair);
        if (!priceService.pairs().contains(marketPair)) {
            throw new PairNotSubscribedException(marketPair, priceService.pairs());
        }
        return marketPair;
    }

    private String checkedSource(String source) {
        String id = source.toLowerCase();
        if (!id.equals(CandleService.INDEX) && !streams.isConfigured(id)) {
            throw new UnknownExchangeException(source);
        }
        return id;
    }
}
