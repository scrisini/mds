package com.mds.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.mds.config.InfluxProperties;
import com.mds.model.MarketPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PriceRepositoryTest {

    private static final MarketPair BTC_USDT = MarketPair.parse("BTC-USDT");
    private static final Instant FROM = Instant.parse("2026-10-21T11:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-21T12:00:00Z");

    private final QueryApi queryApi = mock(QueryApi.class);
    private PriceRepository repository;

    @BeforeEach
    void setUp() {
        InfluxDBClient client = mock(InfluxDBClient.class);
        when(client.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString())).thenReturn(List.of());
        repository = new PriceRepository(client, new InfluxProperties("http://localhost:8086", "t", "mds", "prices"));
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
}
