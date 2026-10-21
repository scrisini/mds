package com.mds.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.mds.config.InfluxProperties;
import com.mds.model.AggregatedPrice;
import com.mds.model.HistoricalAggregate;
import com.mds.model.HistoricalTicker;
import com.mds.model.MarketPair;
import com.mds.model.PriceQuote;
import org.springframework.stereotype.Repository;

/**
 * Archives quotes and aggregates in InfluxDB.
 *
 * <ul>
 *   <li>{@code ticker} — one point per exchange per collection, tagged {@code exchange} and {@code pair}</li>
 *   <li>{@code aggregate} — one point per pair per collection, tagged {@code pair}</li>
 * </ul>
 */
@Repository
public class PriceRepository {

    static final String TICKER = "ticker";
    static final String AGGREGATE = "aggregate";

    /** Flux duration literal, e.g. {@code 30s}, {@code 5m}, {@code 1h}. */
    private static final Pattern WINDOW = Pattern.compile("[1-9][0-9]{0,4}(s|m|h|d|w)");
    private static final Pattern EXCHANGE_ID = Pattern.compile("[a-z0-9]{2,20}");

    private final InfluxDBClient client;
    private final String bucket;

    public PriceRepository(InfluxDBClient client, InfluxProperties properties) {
        this.client = client;
        this.bucket = properties.bucket();
    }

    public void save(List<AggregatedPrice> aggregates) {
        List<Point> points = new ArrayList<>();
        for (AggregatedPrice aggregate : aggregates) {
            points.add(toPoint(aggregate));
            aggregate.quotes().stream().map(PriceRepository::toPoint).forEach(points::add);
        }
        if (!points.isEmpty()) {
            client.getWriteApiBlocking().writePoints(points);
        }
    }

    public List<HistoricalTicker> findTickers(MarketPair pair, Optional<String> exchange, Instant from, Instant to,
                                              Optional<String> window, int limit) {
        exchange.ifPresent(id -> require(EXCHANGE_ID, id, "exchange"));
        String exchangeFilter = exchange
                .map(id -> "\n  |> filter(fn: (r) => r.exchange == \"%s\")".formatted(id))
                .orElse("");
        String flux = query(TICKER, pair, from, to, window, limit, exchangeFilter);

        return records(flux).stream()
                .map(r -> new HistoricalTicker(r.getTime(), (String) r.getValueByKey("exchange"),
                        number(r, "last"), number(r, "bid"), number(r, "ask"),
                        number(r, "high"), number(r, "low"), number(r, "volume")))
                .toList();
    }

    public List<HistoricalAggregate> findAggregates(MarketPair pair, Instant from, Instant to,
                                                    Optional<String> window, int limit) {
        String flux = query(AGGREGATE, pair, from, to, window, limit, "");

        return records(flux).stream()
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

    private List<FluxRecord> records(String flux) {
        return client.getQueryApi().query(flux).stream()
                .flatMap(table -> table.getRecords().stream())
                .toList();
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

    private static void addField(Point point, String name, BigDecimal value) {
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
