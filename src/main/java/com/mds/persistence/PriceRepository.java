package com.mds.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.mds.config.InfluxProperties;
import com.mds.math.Decimal;
import com.mds.model.AggregatedPrice;
import com.mds.model.Candle;
import com.mds.model.CandlePeriod;
import com.mds.model.HistoricalAggregate;
import com.mds.model.HistoricalTicker;
import com.mds.model.MarketPair;
import com.mds.model.PriceQuote;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Repository;

/**
 * Archives quotes and aggregates in InfluxDB.
 *
 * <ul>
 *   <li>{@code ticker} — one point per exchange per collection, tagged {@code exchange} and {@code pair}</li>
 *   <li>{@code aggregate} — one point per pair per collection, tagged {@code pair}</li>
 *   <li>{@code ohlc} — one point per candle, tagged {@code pair}, {@code source} and {@code period},
 *       timestamped at the period start and overwritten as the candle changes</li>
 * </ul>
 *
 * <p>Metrics:
 * <ul>
 *   <li>{@code mds.influx.write} timer, tagged {@code batch} (prices/candles) and {@code outcome}</li>
 *   <li>{@code mds.influx.points} counter of points written, tagged {@code measurement}</li>
 *   <li>{@code mds.influx.query} timer, tagged {@code measurement} and {@code outcome}</li>
 *   <li>{@code mds.influx.query.rows} distribution of rows returned, tagged {@code measurement}</li>
 * </ul>
 */
@Repository
public class PriceRepository {

    static final String TICKER = "ticker";
    static final String AGGREGATE = "aggregate";
    static final String OHLC = "ohlc";

    /** Flux duration literal, e.g. {@code 30s}, {@code 5m}, {@code 1h}. */
    private static final Pattern WINDOW = Pattern.compile("[1-9][0-9]{0,4}(s|m|h|d|w)");
    private static final Pattern EXCHANGE_ID = Pattern.compile("[a-z0-9]{2,20}");

    private final InfluxDBClient client;
    private final String bucket;
    private final MeterRegistry registry;
    private final Counter tickerPoints;
    private final Counter aggregatePoints;
    private final Counter candlePoints;

    public PriceRepository(InfluxDBClient client, InfluxProperties properties, MeterRegistry registry) {
        this.client = client;
        this.bucket = properties.bucket();
        this.registry = registry;
        this.tickerPoints = pointsCounter(TICKER);
        this.aggregatePoints = pointsCounter(AGGREGATE);
        this.candlePoints = pointsCounter(OHLC);
    }

    public void save(List<AggregatedPrice> aggregates) {
        List<Point> points = new ArrayList<>();
        int tickers = 0;
        for (AggregatedPrice aggregate : aggregates) {
            points.add(toPoint(aggregate));
            for (PriceQuote quote : aggregate.quotes()) {
                points.add(toPoint(quote));
                tickers++;
            }
        }
        if (points.isEmpty()) {
            return;
        }
        write("prices", points);
        aggregatePoints.increment(aggregates.size());
        tickerPoints.increment(tickers);
    }

    /** Upserts candles; rewriting a candle replaces the point at its period start. */
    public void saveCandles(List<Candle> candles) {
        if (candles.isEmpty()) {
            return;
        }
        write("candles", candles.stream().map(PriceRepository::toPoint).toList());
        candlePoints.increment(candles.size());
    }

    /** Candles starting in {@code [from, to)}, oldest first. */
    public List<Candle> findCandles(MarketPair pair, String source, CandlePeriod period, Instant from, Instant to,
                                    int limit) {
        require(EXCHANGE_ID, source, "source");
        String flux = """
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "%s" and r.pair == "%s" and r.source == "%s" and r.period == "%s")
                  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
                  |> group()
                  |> sort(columns: ["_time"])
                  |> limit(n: %d)
                """.formatted(escape(bucket), from, to, OHLC, pair, source, period.id(), limit);
        return records(OHLC, flux).stream().map(PriceRepository::toCandle).toList();
    }

    /** The most recent candle of every pair, source and period whose period started since {@code since}. */
    public List<Candle> findLatestCandles(Instant since) {
        String flux = """
                from(bucket: "%s")
                  |> range(start: %s)
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
                  |> group(columns: ["pair", "source", "period"])
                  |> sort(columns: ["_time"])
                  |> tail(n: 1)
                  |> group()
                """.formatted(escape(bucket), since, OHLC);
        return records(OHLC, flux).stream().map(PriceRepository::toCandle).toList();
    }

    public List<HistoricalTicker> findTickers(MarketPair pair, Optional<String> exchange, Instant from, Instant to,
                                              Optional<String> window, int limit) {
        exchange.ifPresent(id -> require(EXCHANGE_ID, id, "exchange"));
        String exchangeFilter = exchange
                .map(id -> "\n  |> filter(fn: (r) => r.exchange == \"%s\")".formatted(id))
                .orElse("");
        String flux = query(TICKER, pair, from, to, window, limit, exchangeFilter);

        return records(TICKER, flux).stream()
                .map(r -> new HistoricalTicker(r.getTime(), (String) r.getValueByKey("exchange"),
                        number(r, "last"), number(r, "bid"), number(r, "ask"),
                        number(r, "high"), number(r, "low"), number(r, "volume")))
                .toList();
    }

    public List<HistoricalAggregate> findAggregates(MarketPair pair, Instant from, Instant to,
                                                    Optional<String> window, int limit) {
        String flux = query(AGGREGATE, pair, from, to, window, limit, "");

        return records(AGGREGATE, flux).stream()
                .map(r -> new HistoricalAggregate(r.getTime(),
                        number(r, "mean"), number(r, "median"), number(r, "vwap"),
                        number(r, "min"), number(r, "max"),
                        number(r, "best_bid"), number(r, "best_ask"), number(r, "sources")))
                .toList();
    }

    /**
     * Builds a Flux query. All interpolated values are validated or come from typed
     * sources ({@link MarketPair} codes are alphanumeric, instants format as RFC 3339).
     */
    private String query(String measurement, MarketPair pair, Instant from, Instant to, Optional<String> window,
                         int limit, String extraFilter) {
        window.ifPresent(w -> require(WINDOW, w, "window"));
        String aggregateWindow = window
                .map(w -> "\n  |> aggregateWindow(every: %s, fn: mean, createEmpty: false)".formatted(w))
                .orElse("");
        return """
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "%s" and r.pair == "%s")%s%s
                  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
                  |> group()
                  |> sort(columns: ["_time"])
                  |> limit(n: %d)
                """.formatted(escape(bucket), from, to, measurement, pair, extraFilter, aggregateWindow, limit);
    }

    private List<FluxRecord> records(String measurement, String flux) {
        List<FluxRecord> records = timed("mds.influx.query", List.of("measurement", measurement),
                () -> client.getQueryApi().query(flux).stream()
                        .flatMap(table -> table.getRecords().stream())
                        .toList());
        DistributionSummary.builder("mds.influx.query.rows")
                .description("Rows returned per InfluxDB query")
                .tag("measurement", measurement)
                .register(registry)
                .record(records.size());
        return records;
    }

    private void write(String batch, List<Point> points) {
        timed("mds.influx.write", List.of("batch", batch), () -> {
            client.getWriteApiBlocking().writePoints(points);
            return null;
        });
    }

    /** Runs {@code call}, recording its duration under {@code name} with an outcome tag. */
    private <T> T timed(String name, List<String> tags, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try {
            T result = call.get();
            outcome = "success";
            return result;
        } finally {
            sample.stop(Timer.builder(name)
                    .tags(tags.toArray(String[]::new))
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }

    private Counter pointsCounter(String measurement) {
        return Counter.builder("mds.influx.points")
                .description("Points written to InfluxDB")
                .tag("measurement", measurement)
                .register(registry);
    }

    private static Point toPoint(PriceQuote quote) {
        Point point = Point.measurement(TICKER)
                .addTag("exchange", quote.exchange())
                .addTag("pair", quote.pair())
                .time(quote.timestamp(), WritePrecision.MS);
        addField(point, "last", quote.last());
        addField(point, "bid", quote.bid());
        addField(point, "ask", quote.ask());
        addField(point, "high", quote.high());
        addField(point, "low", quote.low());
        addField(point, "volume", quote.volume());
        addField(point, "quote_volume", quote.quoteVolume());
        return point;
    }

    private static Point toPoint(AggregatedPrice aggregate) {
        Point point = Point.measurement(AGGREGATE)
                .addTag("pair", aggregate.pair())
                .time(aggregate.timestamp(), WritePrecision.MS)
                .addField("sources", aggregate.sources());
        addField(point, "mean", aggregate.mean());
        addField(point, "median", aggregate.median());
        addField(point, "vwap", aggregate.vwap());
        addField(point, "min", aggregate.min());
        addField(point, "max", aggregate.max());
        addField(point, "best_bid", aggregate.bestBid());
        addField(point, "best_ask", aggregate.bestAsk());
        return point;
    }

    private static Point toPoint(Candle candle) {
        return Point.measurement(OHLC)
                .addTag("pair", candle.pair())
                .addTag("source", candle.source())
                .addTag("period", candle.period().id())
                .time(candle.start(), WritePrecision.S)
                .addField("open", candle.open().doubleValue())
                .addField("high", candle.high().doubleValue())
                .addField("low", candle.low().doubleValue())
                .addField("close", candle.close().doubleValue())
                .addField("updates", candle.updates())
                .addField("complete", candle.complete());
    }

    private static Candle toCandle(FluxRecord r) {
        return new Candle((String) r.getValueByKey("pair"), (String) r.getValueByKey("source"),
                CandlePeriod.parse((String) r.getValueByKey("period")), r.getTime(),
                decimal(r, "open"), decimal(r, "high"), decimal(r, "low"), decimal(r, "close"),
                r.getValueByKey("updates") instanceof Number n ? n.longValue() : 0,
                Boolean.TRUE.equals(r.getValueByKey("complete")));
    }

    private static Decimal decimal(FluxRecord record, String key) {
        return Decimal.ofDouble(((Number) record.getValueByKey(key)).doubleValue());
    }

    private static void addField(Point point, String name, Decimal value) {
        if (value != null) {
            point.addField(name, value.doubleValue());
        }
    }

    private static Double number(FluxRecord record, String key) {
        return record.getValueByKey(key) instanceof Number n ? n.doubleValue() : null;
    }

    private static void require(Pattern pattern, String value, String name) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid %s '%s'".formatted(name, value));
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
