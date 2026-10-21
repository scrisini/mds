package com.mds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.mds.config.MdsProperties;
import com.mds.exchange.MarketStreamManager;
import com.mds.math.Decimal;
import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PriceStreamServiceTest {

    private static final MarketPair BTC_USDT = MarketPair.parse("BTC-USDT");

    private final TestScheduler time = new TestScheduler();
    private final QuoteBook book = new QuoteBook(Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private PriceStreamService service;

    @BeforeEach
    void setUp() {
        MdsProperties properties = new MdsProperties(List.of("binance"), List.of("BTC/USDT"),
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofDays(1),
                new MdsProperties.Archive(false, Duration.ofSeconds(5)),
                new MdsProperties.Stream(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(60),
                        Duration.ofMinutes(30), 1),
                new MdsProperties.DecimalSettings(8, RoundingMode.HALF_EVEN),
                new MdsProperties.Ohlc(ZoneOffset.UTC, Duration.ofSeconds(10)));
        MarketStreamManager streams = mock(MarketStreamManager.class);
        when(streams.exchangesFor(BTC_USDT)).thenReturn(List.of("binance"));
        PriceService prices = new PriceService(streams, book, new PriceAggregator(), properties,
                Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
        service = new PriceStreamService(book, prices, properties, registry, time, Schedulers.trampoline());
    }

    @Test
    void sendsCurrentPriceImmediatelyThenLatestOncePerInterval() {
        book.onTrade("binance", BTC_USDT, new BigDecimal("100"));
        TestSubscriber<AggregatedPrice> client = service.updates(BTC_USDT, Duration.ofSeconds(1)).test();

        client.assertValueCount(1);
        assertThat(client.values().getFirst().mean()).isEqualTo(Decimal.parse("100"));

        for (int price = 101; price <= 110; price++) {
            book.onTrade("binance", BTC_USDT, BigDecimal.valueOf(price));
        }
        client.assertValueCount(1); // still inside the throttle window

        time.advanceTimeBy(1, TimeUnit.SECONDS);
        client.assertValueCount(2);
        assertThat(client.values().getLast().mean()).isEqualTo(Decimal.parse("110"));

        time.advanceTimeBy(5, TimeUnit.SECONDS);
        client.assertValueCount(2); // no updates, nothing sent
    }

    @Test
    void skipsUpdatesWhileNoPriceIsAvailable() {
        TestSubscriber<AggregatedPrice> client = service.updates(BTC_USDT, Duration.ofSeconds(1)).test();

        client.assertNoValues().assertNotComplete();
    }

    @Test
    void validatesSubscriptionRequests() {
        assertThatThrownBy(() -> service.subscribe(MarketPair.parse("DOGE-USDT"), Optional.empty()))
                .isInstanceOf(PairNotSubscribedException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.subscribe(BTC_USDT, Optional.of(Duration.ofMillis(10))));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.subscribe(BTC_USDT, Optional.of(Duration.ofMinutes(5))));
    }

    @Test
    void enforcesSubscriberLimit() {
        service.subscribe(BTC_USDT, Optional.empty());

        assertThat(registry.get("mds.stream.subscribers").gauge().value()).isEqualTo(1);
        assertThatThrownBy(() -> service.subscribe(BTC_USDT, Optional.empty()))
                .isInstanceOf(StreamCapacityException.class);
        assertThat(registry.get("mds.stream.subscribers").gauge().value()).isEqualTo(1);
    }
}
