package com.mds.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.mds.model.ExchangeState;
import com.mds.model.ExchangeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class StreamHealthIndicatorTest {

    private static final Instant T = Instant.parse("2026-10-30T12:00:00Z");

    private final MarketStreamManager streams = mock(MarketStreamManager.class);
    private final StreamHealthIndicator indicator = new StreamHealthIndicator(streams);

    @Test
    void upWhileAnyExchangeIsConnectedAndListsEveryExchange() {
        ExchangeStatus kraken = new ExchangeStatus("kraken", ExchangeState.CONNECTED, true, List.of("BTC-USD"), T, null);
        ExchangeStatus binance = new ExchangeStatus("binance", ExchangeState.FAILED, false, List.of(), null,
                "could not initialise (MDS-3002)");
        when(streams.status()).thenReturn(List.of(binance, kraken));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("kraken", kraken).containsEntry("binance", binance);
    }

    @Test
    void downWhenNoExchangeIsConnected() {
        when(streams.status()).thenReturn(List.of(
                new ExchangeStatus("kraken", ExchangeState.STALE, true, List.of("BTC-USD"), T, null),
                new ExchangeStatus("bitstamp", ExchangeState.DISCONNECTED, false, List.of("BTC-USD"), T, null)));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
