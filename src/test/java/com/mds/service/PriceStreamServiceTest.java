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
import java.util.ArrayList;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PriceStreamServiceTest {

    private static final MarketPair BTC_USDT = MarketPair.parse("BTC-USDT");

    private final TestScheduler time = new TestScheduler();
    private final QuoteBook book = new QuoteBook(Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PriceStreamService service = service(true, 2);

    private PriceStreamService service(boolean allowUnthrottled, int maxSubscribers) {
        MdsProperties properties = new MdsProperties(List.of("binance"), List.of("BTC/USDT"),
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofDays(1),
                new MdsProperties.Archive(false, Duration.ofSeconds(5)),
                new MdsProperties.Stream(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(60),
                        Duration.ofMinutes(30), maxSubscribers, allowUnthrottled, List.of("*")),
                new MdsProperties.DecimalSettings(8, RoundingMode.HALF_EVEN),
                new MdsProperties.Ohlc(ZoneOffset.UTC, Duration.ofSeconds(10)),
                new MdsProperties.SocksProxy("", 1080));
        MarketStreamManager streams = mock(MarketStreamManager.class);
        when(streams.exchangesFor(BTC_USDT)).thenReturn(List.of("binance"));
        PriceService prices = new PriceService(streams, book, new PriceAggregator(), properties,
                Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
        return new PriceStreamService(book, prices, properties, registry, time, Schedulers.trampoline());
    }

    // ---- rate resolution ----

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 10})
    void acceptsWholeRatesUpToTheMinimumInterval(int perSecond) {
        StreamRate rate = service.resolveRate(Optional.of(Integer.toString(perSecond)), Optional.empty());

        assertThat(rate.minInterval()).isEqualTo(Duration.ofSeconds(1).dividedBy(perSecond));
        assertThat(rate).hasToString(perSecond + "/s");
    }

    @Test
    void acceptsUnlimited() {
        StreamRate rate = service.resolveRate(Optional.of("Unlimited"), Optional.empty());

        assertThat(rate.throttled()).isFalse();
        assertThat(rate).hasToString("unlimited");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "11", "1.5", "fast", ""})
    void rejectsInvalidRates(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolveRate(Optional.of(value), Optional.empty()))
                .withMessageContaining("rate");
    }

    @Test
    void rejectsUnlimitedWhenDisabled() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service(false, 2).resolveRate(Optional.of("unlimited"), Optional.empty()))
                .withMessageContaining("disabled");
    }

    @Test
    void keepsTheThrottleIntervalFormAndItsDefault() {
        assertThat(service.resolveRate(Optional.empty(), Optional.of(Duration.ofMillis(1500))))
                .hasToString("every PT1.5S");
        assertThat(service.resolveRate(Optional.empty(), Optional.empty())).hasToString("1/s");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolveRate(Optional.empty(), Optional.of(Duration.ofMillis(10))));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolveRate(Optional.of("2"), Optional.of(Duration.ofSeconds(1))))
                .withMessageContaining("not both");
    }

    // ---- delivery ----

    @Test
    void sendsCurrentPriceImmediatelyThenLatestOncePerInterval() {
        trade("100");
        TestSubscriber<AggregatedPrice> client = service.updates(BTC_USDT, StreamRate.every(Duration.ofSeconds(1))).test();

        client.assertValueCount(1);
        assertThat(client.values().getFirst().mean()).isEqualTo(Decimal.parse("100"));

        for (int price = 101; price <= 110; price++) {
            trade(Integer.toString(price));
        }
        client.assertValueCount(1); // still inside the throttle window

        time.advanceTimeBy(1, TimeUnit.SECONDS);
        client.assertValueCount(2);
        assertThat(client.values().getLast().mean()).isEqualTo(Decimal.parse("110"));

        time.advanceTimeBy(5, TimeUnit.SECONDS);
        client.assertValueCount(2); // no updates, nothing sent
    }

    @Test
    void rateOfTwoPerSecondDeliversAtMostOneUpdatePerHalfSecond() {
        trade("100");
        TestSubscriber<AggregatedPrice> client = service.updates(BTC_USDT, StreamRate.perSecond(2)).test();

        // a burst of updates every 100ms for 3 seconds
        for (int tick = 1; tick <= 30; tick++) {
            trade(Integer.toString(100 + tick));
            time.advanceTimeBy(100, TimeUnit.MILLISECONDS);
        }

        // the first at once, then one per 500ms window: 1 + 3s / 0.5s
        assertThat(client.values()).hasSize(7);
        assertThat(client.values().getLast().mean()).isEqualTo(Decimal.parse("130"));
    }

    @Test
    void unthrottledDeliversEveryUpdate() {
        trade("100");
        TestSubscriber<AggregatedPrice> client = service.updates(BTC_USDT, StreamRate.unthrottled()).test();

        for (int price = 101; price <= 120; price++) {
            trade(Integer.toString(price));
        }

        assertThat(client.values()).hasSize(21);
        assertThat(client.values()).extracting(AggregatedPrice::mean)
                .startsWith(Decimal.parse("100"), Decimal.parse("101"))
                .endsWith(Decimal.parse("120"));
    }

    @Test
    void skipsUpdatesWhileNoPriceIsAvailable() {
        TestSubscriber<AggregatedPrice> client = service.updates(BTC_USDT, StreamRate.perSecond(1)).test();

        client.assertNoValues().assertNotComplete();
    }

    // ---- subscriptions ----

    @Test
    void openDeliversToTheListenerAndCountsPerTransport() {
        trade("100");
        List<AggregatedPrice> received = new ArrayList<>();

        StreamSubscription subscription = service.open(BTC_USDT, StreamRate.unthrottled(),
                PriceStreamService.WEBSOCKET, received::add, e -> { });
        trade("101");

        assertThat(received).extracting(AggregatedPrice::mean).containsExactly(Decimal.parse("100"), Decimal.parse("101"));
        assertThat(gauge(PriceStreamService.WEBSOCKET)).isEqualTo(1);
        assertThat(gauge(PriceStreamService.SSE)).isZero();
        assertThat(registry.get("mds.stream.events").tag("transport", "websocket").counter().count()).isEqualTo(2);

        subscription.close();
        subscription.close(); // idempotent
        trade("102");
        assertThat(received).hasSize(2);
        assertThat(gauge(PriceStreamService.WEBSOCKET)).isZero();
    }

    @Test
    void aFailingListenerEndsItsSubscription() {
        trade("100");
        List<Throwable> errors = new ArrayList<>();

        service.open(BTC_USDT, StreamRate.unthrottled(), PriceStreamService.SSE,
                price -> { throw new java.io.IOException("client went away"); }, errors::add);

        assertThat(errors).singleElement().satisfies(e -> assertThat(e).hasMessage("client went away"));
        assertThat(gauge(PriceStreamService.SSE)).isZero();
    }

    @Test
    void validatesSubscriptions() {
        assertThatThrownBy(() -> service.open(MarketPair.parse("DOGE-USDT"), StreamRate.perSecond(1),
                PriceStreamService.SSE, p -> { }, e -> { }))
                .isInstanceOf(PairNotSubscribedException.class);
    }

    @Test
    void enforcesSubscriberLimitAcrossTransports() {
        service.open(BTC_USDT, StreamRate.perSecond(1), PriceStreamService.SSE, p -> { }, e -> { });
        service.open(BTC_USDT, StreamRate.perSecond(1), PriceStreamService.WEBSOCKET, p -> { }, e -> { });

        assertThatThrownBy(() -> service.open(BTC_USDT, StreamRate.perSecond(1), PriceStreamService.WEBSOCKET,
                p -> { }, e -> { }))
                .isInstanceOf(StreamCapacityException.class);
        assertThat(gauge(PriceStreamService.SSE) + gauge(PriceStreamService.WEBSOCKET)).isEqualTo(2);
    }

    private double gauge(String transport) {
        return registry.get("mds.stream.subscribers").tag("transport", transport).gauge().value();
    }

    private void trade(String price) {
        book.onTrade("binance", BTC_USDT, new BigDecimal(price));
    }
}
