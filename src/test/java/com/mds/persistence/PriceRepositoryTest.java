package com.mds.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import com.mds.config.InfluxProperties;
import com.mds.math.Decimal;
import com.mds.model.Candle;
import com.mds.model.CandlePeriod;
import com.mds.model.MarketPair;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PriceRepositoryTest {

    private static final MarketPair BTC_USDT = MarketPair.parse("BTC-USDT");
    private static final Instant FROM = Instant.parse("2026-10-21T11:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-21T12:00:00Z");

    private final InfluxDBClient client = mock(InfluxDBClient.class);
    private final QueryApi queryApi = mock(QueryApi.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private PriceRepository repository;

    @BeforeEach
    void setUp() {
        when(client.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString())).thenReturn(List.of());
        repository = new PriceRepository(client, new InfluxProperties("http://localhost:8086", "t", "mds", "prices"),
                registry);
    }

    @Test
    void buildsTickerQueryWithFiltersAndWindow() {
        repository.findTickers(BTC_USDT, Optional.of("kraken"), FROM, TO, Optional.of("5m"), 100);

        ArgumentCaptor<String> flux = ArgumentCaptor.forClass(String.class);
        verify(queryApi).query(flux.capture());
        assertThat(flux.getValue())
                .contains("from(bucket: \"prices\")")
                .contains("range(start: 2026-10-21T11:00:00Z, stop: 2026-10-21T12:00:00Z)")
                .contains("r._measurement == \"ticker\" and r.pair == \"BTC/USDT\"")
                .contains("r.exchange == \"kraken\"")
                .contains("aggregateWindow(every: 5m, fn: mean, createEmpty: false)")
                .contains("limit(n: 100)");
    }

    @Test
    void rejectsValuesThatCouldAlterTheQuery() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                repository.findAggregates(BTC_USDT, FROM, TO, Optional.of("1m) |> drop("), 10));
        assertThatIllegalArgumentException().isThrownBy(() ->
                repository.findTickers(BTC_USDT, Optional.of("x\" or true"), FROM, TO, Optional.empty(), 10));
    }

    @Test
    void recordsQueryTimingAndRowCount() {
        repository.findAggregates(BTC_USDT, FROM, TO, Optional.empty(), 10);

        assertThat(registry.get("mds.influx.query").tag("measurement", "aggregate").tag("outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("mds.influx.query.rows").summary().count()).isEqualTo(1);
    }

    @Test
    void recordsFailedQueriesWithErrorOutcome() {
        when(queryApi.query(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> repository.findAggregates(BTC_USDT, FROM, TO, Optional.empty(), 10))
                .hasMessage("connection refused");
        assertThat(registry.get("mds.influx.query").tag("outcome", "error").timer().count()).isEqualTo(1);
    }

    @Test
    void writesCandlesAtPeriodStart() {
        WriteApiBlocking writeApi = mock(WriteApiBlocking.class);
        when(client.getWriteApiBlocking()).thenReturn(writeApi);
        Candle candle = new Candle("BTC/USDT", "index", CandlePeriod.DAY, Instant.parse("2026-10-21T00:00:00Z"),
                Decimal.parse("100"), Decimal.parse("110.5"), Decimal.parse("95"), Decimal.parse("105"), 42, false);

        repository.saveCandles(List.of(candle));

        ArgumentCaptor<List<Point>> points = pointsCaptor();
        verify(writeApi).writePoints(points.capture());
        assertThat(points.getValue().getFirst().toLineProtocol()).isEqualTo(
                "ohlc,pair=BTC/USDT,period=day,source=index "
                        + "close=105.0,complete=false,high=110.5,low=95.0,open=100.0,updates=42i 1792540800");
        assertThat(registry.get("mds.influx.points").tag("measurement", "ohlc").counter().count()).isEqualTo(1);
        assertThat(registry.get("mds.influx.write").tag("batch", "candles").timer().count()).isEqualTo(1);
    }

    @Test
    void buildsCandleQuery() {
        repository.findCandles(BTC_USDT, "kraken", CandlePeriod.WEEK, FROM, TO, 52);

        ArgumentCaptor<String> flux = ArgumentCaptor.forClass(String.class);
        verify(queryApi).query(flux.capture());
        assertThat(flux.getValue())
                .contains("r._measurement == \"ohlc\" and r.pair == \"BTC/USDT\" and r.source == \"kraken\" "
                        + "and r.period == \"week\"")
                .contains("limit(n: 52)");
    }

    @Test
    void recoveryQueryTakesLatestCandleOfEachSeries() {
        repository.findLatestCandles(FROM);

        ArgumentCaptor<String> flux = ArgumentCaptor.forClass(String.class);
        verify(queryApi).query(flux.capture());
        assertThat(flux.getValue())
                .contains("group(columns: [\"pair\", \"source\", \"period\"])")
                .contains("tail(n: 1)")
                .doesNotContain("complete == false");
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Point>> pointsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
