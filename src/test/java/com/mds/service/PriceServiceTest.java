package com.mds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.mds.config.MdsProperties;
import com.mds.exchange.MarketStreamManager;
import com.mds.math.Decimal;
import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.model.QuoteFailure;
import com.mds.support.TestClusters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PriceServiceTest {

    private static final MarketPair BTC_USDT = MarketPair.parse("BTC-USDT");
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private final MarketStreamManager streams = mock(MarketStreamManager.class);
    private final Clock clock = mock(Clock.class);
    private final QuoteBook book = new QuoteBook(clock, new SimpleMeterRegistry());
    private PriceService service;

    @BeforeEach
    void setUp() {
        MdsProperties properties = new MdsProperties(List.of("binance", "kraken", "kucoin"), List.of("BTC/USDT"),
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(60),
                new MdsProperties.Archive(false, Duration.ofSeconds(5)),
                new MdsProperties.Stream(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(60),
                        Duration.ofMinutes(30), 2, true, List.of("*"), Duration.ofSeconds(30)),
                new MdsProperties.DecimalSettings(8, RoundingMode.HALF_EVEN),
                new MdsProperties.Ohlc(ZoneOffset.UTC, Duration.ofSeconds(10)),
                new MdsProperties.SocksProxy("", 1080), TestClusters.standalone(), MdsProperties.Mode.DEVELOPMENT);
        service = new PriceService(streams, book, new PriceAggregator(), properties, clock);
        when(streams.exchangesFor(BTC_USDT)).thenReturn(List.of("binance", "kraken", "kucoin"));
    }

    @Test
    void aggregatesFreshQuotesAndReportsStaleOrMissingOnes() {
        when(clock.instant()).thenReturn(NOW.minusSeconds(120));
        book.onTrade("kraken", BTC_USDT, new BigDecimal("99"));
        when(clock.instant()).thenReturn(NOW.minusSeconds(1));
        book.onTrade("binance", BTC_USDT, new BigDecimal("100"));
        when(clock.instant()).thenReturn(NOW);

        AggregatedPrice result = service.aggregate(BTC_USDT);

        assertThat(result.sources()).isEqualTo(1);
        assertThat(result.mean()).isEqualTo(Decimal.parse("100"));
        assertThat(result.failures()).extracting("exchange", "reason").containsExactly(
                tuple("kraken", "stale, last update 2026-10-21T11:58:00Z"),
                tuple("kucoin", "awaiting first update"));
    }

    @Test
    void withoutAFreshPriceAggregateFailsWithTheReasonsAndCurrentIsEmpty() {
        when(clock.instant()).thenReturn(NOW);

        assertThatThrownBy(() -> service.aggregate(BTC_USDT))
                .isInstanceOf(PriceUnavailableException.class)
                .extracting(e -> ((PriceUnavailableException) e).failures())
                .asInstanceOf(InstanceOfAssertFactories.list(QuoteFailure.class))
                .extracting(QuoteFailure::exchange)
                .containsExactly("binance", "kraken", "kucoin");
        assertThat(service.current(BTC_USDT)).isEmpty();
    }

    @Test
    void omitsPairsWithoutFreshQuotesFromBulkAggregates() {
        when(clock.instant()).thenReturn(NOW);

        assertThat(service.aggregates()).isEmpty();
    }

    @Test
    void rejectsPairsThatAreNotSubscribed() {
        assertThatThrownBy(() -> service.aggregate(MarketPair.parse("DOGE-USDT")))
                .isInstanceOf(PairNotSubscribedException.class);
    }

    @Test
    void rejectsUnknownExchange() {
        when(streams.isConfigured("nope")).thenReturn(false);

        assertThatThrownBy(() -> service.quote("nope", BTC_USDT)).isInstanceOf(UnknownExchangeException.class);
    }
}
