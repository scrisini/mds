package com.mds.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.mds.model.HistoricalAggregate;
import com.mds.model.HistoricalTicker;
import com.mds.model.MarketPair;
import com.mds.persistence.PriceRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Archived prices from InfluxDB. {@code from}/{@code to} are ISO-8601 instants and default
 * to the last hour; {@code window} (e.g. {@code 1m}, {@code 1h}) averages rows into buckets.
 */
@Tag(name = "History", description = "Prices archived in InfluxDB every mds.archive.interval")
@RestController
@RequestMapping("/api/v1/history/{pair}")
public class HistoryController {

    private static final String FROM_TO = "from and to are ISO-8601 instants and default to the last hour; window "
            + "(e.g. 1m, 1h) averages rows into buckets.";

    private static final Duration DEFAULT_RANGE = Duration.ofHours(1);
    private static final int MAX_LIMIT = 10_000;

    private final PriceRepository repository;
    private final Clock clock;

    public HistoryController(PriceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Operation(summary = "Archived aggregates", description = FROM_TO)
    @GetMapping
    public List<HistoricalAggregate> aggregates(
            @Parameter(example = "BTC-USDT") @PathVariable String pair,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> to,
            @RequestParam Optional<String> window,
            @RequestParam(defaultValue = "1000") int limit) {
        Range range = Range.of(from, to, clock.instant());
        return repository.findAggregates(MarketPair.parse(pair), range.from(), range.to(), window, checked(limit));
    }

    @Operation(summary = "Archived exchange tickers", description = FROM_TO)
    @GetMapping("/tickers")
    public List<HistoricalTicker> tickers(
            @Parameter(example = "BTC-USDT") @PathVariable String pair,
            @Parameter(description = "Only this exchange; all when absent", example = "kraken")
            @RequestParam Optional<String> exchange,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Optional<Instant> to,
            @RequestParam Optional<String> window,
            @RequestParam(defaultValue = "1000") int limit) {
        Range range = Range.of(from, to, clock.instant());
        return repository.findTickers(MarketPair.parse(pair), exchange.map(String::toLowerCase),
                range.from(), range.to(), window, checked(limit));
    }

    private static int checked(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    private record Range(Instant from, Instant to) {

        static Range of(Optional<Instant> from, Optional<Instant> to, Instant now) {
            Instant end = to.orElse(now);
            Instant start = from.orElse(end.minus(DEFAULT_RANGE));
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("'from' must be before 'to'");
            }
            return new Range(start, end);
        }
    }
}
