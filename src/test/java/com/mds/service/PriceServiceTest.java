package com.mds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mds.config.MdsProperties;
import com.mds.exchange.ExchangeRegistry;
import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.service.marketdata.MarketDataService;

@SuppressWarnings("deprecation") // stubs getTicker(CurrencyPair), see PriceService#fetch
class PriceServiceTest {

    private static final MarketPair BTC_USDT = MarketPair.parse("BTC-USDT");
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private final ExchangeRegistry registry = mock(ExchangeRegistry.class);
    private ExecutorService executor;
    private PriceService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        MdsProperties properties = new MdsProperties(List.of("binance", "kraken"), List.of("BTC/USDT"),
                Duration.ofMillis(500), new MdsProperties.Collector(false, Duration.ofSeconds(30)));
        service = new PriceService(registry, new PriceAggregator(), properties, executor,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void aggregatesSuccessfulQuotesAndRecordsFailures() throws IOException {
        register("binance", tickerReturning("100"));
        MarketDataService failing = mock(MarketDataService.class);
        when(failing.getTicker(any(CurrencyPair.class))).thenThrow(new IOException("connection refused"));
        register("kraken", failing);
        when(registry.supporting(BTC_USDT)).thenReturn(List.of("binance", "kraken"));

        AggregatedPrice result = service.refresh(BTC_USDT);

        assertThat(result.sources()).isEqualTo(1);
        assertThat(result.mean()).isEqualByComparingTo("100");
        assertThat(result.timestamp()).isEqualTo(NOW);
        assertThat(result.failures()).singleElement()
                .satisfies(f -> {
                    assertThat(f.exchange()).isEqualTo("kraken");
                    assertThat(f.reason()).isEqualTo("IOException: connection refused");
                });
        assertThat(service.latestForConfiguredPairs()).containsExactly(result);
    }

    @Test
    void reportsSlowExchangesAsTimedOut() throws IOException {
        MarketDataService slow = mock(MarketDataService.class);
        when(slow.getTicker(any(CurrencyPair.class))).thenAnswer(invocation -> {
            Thread.sleep(2_000);
            return null;
        });
        register("kraken", slow);
        when(registry.supporting(BTC_USDT)).thenReturn(List.of("kraken"));

        assertThatThrownBy(() -> service.refresh(BTC_USDT))
                .isInstanceOf(PriceUnavailableException.class)
                .satisfies(e -> assertThat(((PriceUnavailableException) e).failures())
                        .singleElement()
                        .satisfies(f -> assertThat(f.reason()).isEqualTo("timed out")));
    }

    @Test
    void rejectsUnknownExchange() {
        when(registry.find("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.quote("nope", BTC_USDT)).isInstanceOf(UnknownExchangeException.class);
    }

    private void register(String id, MarketDataService marketData) {
        Exchange exchange = mock(Exchange.class);
        when(exchange.getMarketDataService()).thenReturn(marketData);
        when(registry.find(id)).thenReturn(Optional.of(exchange));
    }

    private static MarketDataService tickerReturning(String last) throws IOException {
        MarketDataService marketData = mock(MarketDataService.class);
        Ticker ticker = new Ticker.Builder()
                .instrument(BTC_USDT.toCurrencyPair())
                .last(new BigDecimal(last))
                .build();
        when(marketData.getTicker(any(CurrencyPair.class))).thenReturn(ticker);
        return marketData;
    }
}
