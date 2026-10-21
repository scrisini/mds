package com.mds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.mds.config.MdsProperties;
import com.mds.exchange.MarketStreamManager;
import com.mds.math.Decimal;
import com.mds.model.Candle;
import com.mds.model.CandlePeriod;
import com.mds.model.MarketPair;
import com.mds.persistence.PriceRepository;
import com.mds.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CandleServiceTest {

    private static final MarketPair BTC_USD = MarketPair.parse("BTC-USD");
    private static final Instant MORNING = Instant.parse("2026-10-21T09:00:00Z");
    private static final Instant TODAY = Instant.parse("2026-10-21T00:00:00Z");

    private final MutableClock clock = new MutableClock(MORNING);
    private final QuoteBook book = new QuoteBook(clock);
    private final PriceRepository repository = mock(PriceRepository.class);
    private final List<List<Candle>> written = new ArrayList<>();
    private CandleService service;

    @BeforeEach
    void setUp() {
        MdsProperties properties = new MdsProperties(List.of("kraken", "bitstamp"), List.of("BTC/USD"),
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofDays(400),
                new MdsProperties.Archive(false, Duration.ofSeconds(5)),
                new MdsProperties.Stream(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(60),
                        Duration.ofMinutes(30), 10),
                new MdsProperties.DecimalSettings(8, RoundingMode.HALF_EVEN),
                new MdsProperties.Ohlc(ZoneOffset.UTC, Duration.ofSeconds(10)),
                new MdsProperties.SocksProxy("", 1080));
        MarketStreamManager streams = mock(MarketStreamManager.class);
        when(streams.exchangesFor(BTC_USD)).thenReturn(List.of("bitstamp", "kraken"));
        PriceService prices = new PriceService(streams, book, new PriceAggregator(), properties, clock);
        service = new CandleService(book, prices, repository, properties, clock);
        when(repository.findLatestCandles(any())).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(inv -> written.add(List.copyOf(inv.getArgument(0))))
                .when(repository).saveCandles(anyList());
    }

    @AfterEach
    void tearDown() {
        if (service.isRunning()) {
            service.stop();
        }
    }

    @Test
    void buildsOhlcPerExchangeAndForTheIndex() {
        service.start();
        trade("kraken", "100");
        trade("kraken", "110");
        trade("kraken", "95");
        trade("kraken", "105");
        trade("bitstamp", "107");

        Candle kraken = current("kraken", CandlePeriod.DAY);
        assertThat(List.of(kraken.open(), kraken.high(), kraken.low(), kraken.close()))
                .containsExactly(d("100"), d("110"), d("95"), d("105"));
        assertThat(kraken.updates()).isEqualTo(4);
        assertThat(kraken.start()).isEqualTo(TODAY);

        // index = median of fresh lasts: 100, 110, 95, 105 (kraken alone), then (105 + 107) / 2
        Candle index = current(CandleService.INDEX, CandlePeriod.DAY);
        assertThat(List.of(index.open(), index.high(), index.low(), index.close()))
                .containsExactly(d("100"), d("110"), d("95"), d("106"));

        assertThat(service.current(BTC_USD, "kraken")).extracting(Candle::period)
                .containsExactly(CandlePeriod.values());
    }

    @Test
    void rollsOverToANewCandleWhenAnUpdateCrossesTheBoundary() {
        service.start();
        trade("kraken", "100");
        clock.set(Instant.parse("2026-10-22T00:00:01Z"));
        trade("kraken", "120");

        assertThat(current("kraken", CandlePeriod.DAY).open()).isEqualTo(d("120"));
        assertThat(current("kraken", CandlePeriod.WEEK).open()).isEqualTo(d("100")); // same week

        service.flush();
        assertThat(lastBatch()).anySatisfy(c -> {
            assertThat(c.source()).isEqualTo("kraken");
            assertThat(c.period()).isEqualTo(CandlePeriod.DAY);
            assertThat(c.start()).isEqualTo(TODAY);
            assertThat(c.close()).isEqualTo(d("100"));
            assertThat(c.complete()).isTrue();
        });
    }

    @Test
    void endedPeriodWithoutUpdatesIsClosedAndNextOneSeededAtLastClose() {
        service.start();
        trade("kraken", "100");
        trade("kraken", "104");
        clock.set(Instant.parse("2026-10-22T00:00:00Z"));

        service.flush();

        assertThat(lastBatch()).filteredOn(c -> c.period() == CandlePeriod.DAY && c.start().equals(TODAY))
                .allSatisfy(c -> assertThat(c.complete()).isTrue())
                .hasSize(2); // kraken + index
        Candle tomorrow = current("kraken", CandlePeriod.DAY);
        assertThat(tomorrow.start()).isEqualTo(Instant.parse("2026-10-22T00:00:00Z"));
        assertFlat(tomorrow, "104");
        assertThat(lastBatch()).contains(tomorrow);
    }

    @Test
    void firstRealPriceReplacesTheCarriedForwardPlaceholder() {
        service.start();
        trade("kraken", "100");
        clock.set(Instant.parse("2026-10-22T00:00:00Z"));
        service.flush();

        clock.set(Instant.parse("2026-10-22T08:00:00Z"));
        trade("kraken", "90");
        trade("kraken", "93");

        Candle day = current("kraken", CandlePeriod.DAY);
        assertThat(List.of(day.open(), day.high(), day.low(), day.close()))
                .containsExactly(d("90"), d("93"), d("90"), d("93"));
        assertThat(day.updates()).isEqualTo(2);
    }

    @Test
    void fillsEveryPeriodWithoutUpdatesUntilPricesResume() {
        service.start();
        trade("kraken", "100");
        for (int day = 22; day <= 24; day++) {
            clock.set(Instant.parse("2026-10-%dT00:00:05Z".formatted(day)));
            service.flush();
        }

        List<Candle> days = written.stream().flatMap(List::stream)
                .filter(c -> c.source().equals("kraken") && c.period() == CandlePeriod.DAY && c.complete())
                .distinct().toList();
        assertThat(days).extracting(Candle::start).containsExactly(
                TODAY, Instant.parse("2026-10-22T00:00:00Z"), Instant.parse("2026-10-23T00:00:00Z"));
        days.subList(1, 3).forEach(c -> assertFlat(c, "100"));
    }

    @Test
    void indexIsCarriedForwardWhenNoExchangeHasAFreshPrice() {
        service.start();
        trade("kraken", "100");
        // a quiet day: the kraken quote goes stale, so there is no index price either
        clock.set(Instant.parse("2026-10-22T00:00:05Z"));
        service.flush();

        assertFlat(current(CandleService.INDEX, CandlePeriod.DAY), "100");
    }

    @Test
    void retriesCompletedCandlesAfterAFailedWrite() {
        service.start();
        trade("kraken", "100");
        clock.set(Instant.parse("2026-10-22T00:00:05Z"));
        doThrow(new RuntimeException("influx down")).when(repository).saveCandles(anyList());
        service.flush();

        org.mockito.Mockito.reset(repository);
        ArgumentCaptor<List<Candle>> batch = captor();
        service.flush();

        verify(repository).saveCandles(batch.capture());
        assertThat(batch.getValue()).anySatisfy(c -> {
            assertThat(c.period()).isEqualTo(CandlePeriod.DAY);
            assertThat(c.complete()).isTrue();
        });
    }

    @Test
    void resumesCurrentCandlesAndCompletesStaleOnesOnStartup() {
        Candle yesterday = new Candle("BTC/USD", "kraken", CandlePeriod.DAY, TODAY.minus(Duration.ofDays(1)),
                d("90"), d("99"), d("80"), d("95"), 50, false);
        Candle thisMonth = new Candle("BTC/USD", "kraken", CandlePeriod.MONTH,
                Instant.parse("2026-10-01T00:00:00Z"), d("70"), d("130"), d("60"), d("95"), 900, false);
        when(repository.findLatestCandles(any())).thenReturn(List.of(yesterday, thisMonth));

        service.start();
        trade("kraken", "140");

        Candle month = current("kraken", CandlePeriod.MONTH);
        assertThat(List.of(month.open(), month.high(), month.low(), month.close()))
                .containsExactly(d("70"), d("140"), d("60"), d("140"));
        assertThat(month.updates()).isEqualTo(901);

        service.flush();
        assertThat(lastBatch()).contains(yesterday.completed());
    }

    @Test
    void backFillsPeriodsMissedWhileDown() {
        // last seen three days ago, before the service went down
        Candle lastSeen = new Candle("BTC/USD", "kraken", CandlePeriod.DAY, Instant.parse("2026-10-18T00:00:00Z"),
                d("90"), d("99"), d("80"), d("95"), 50, false);
        when(repository.findLatestCandles(any())).thenReturn(List.of(lastSeen));

        service.start();
        service.flush();

        List<Candle> kraken = lastBatch().stream()
                .filter(c -> c.source().equals("kraken") && c.period() == CandlePeriod.DAY).toList();
        assertThat(kraken).extracting(Candle::start).containsExactly(
                Instant.parse("2026-10-18T00:00:00Z"), Instant.parse("2026-10-19T00:00:00Z"),
                Instant.parse("2026-10-20T00:00:00Z"), TODAY);
        assertThat(kraken.getFirst()).isEqualTo(lastSeen.completed());
        assertThat(kraken.subList(1, 3)).allSatisfy(c -> {
            assertFlat(c, "95");
            assertThat(c.complete()).isTrue();
        });
        assertFlat(kraken.getLast(), "95");
        assertThat(kraken.getLast().complete()).isFalse();
    }

    @Test
    void resumesAfterACompletedCandleWhenTheNextWasNeverWritten() {
        // e.g. the process died between closing yesterday and the next flush
        Candle yesterday = new Candle("BTC/USD", "kraken", CandlePeriod.DAY, TODAY.minus(Duration.ofDays(1)),
                d("90"), d("99"), d("80"), d("95"), 50, true);
        when(repository.findLatestCandles(any())).thenReturn(List.of(yesterday));

        service.start();
        service.flush();

        assertThat(lastBatch()).doesNotContain(yesterday);
        Candle today = current("kraken", CandlePeriod.DAY);
        assertThat(today.start()).isEqualTo(TODAY);
        assertFlat(today, "95");
    }

    @Test
    void stopsCarryingForwardSourcesNoLongerConfigured() {
        Candle removedExchange = new Candle("BTC/USD", "gemini", CandlePeriod.DAY, TODAY.minus(Duration.ofDays(2)),
                d("90"), d("99"), d("80"), d("95"), 50, false);
        when(repository.findLatestCandles(any())).thenReturn(List.of(removedExchange));

        service.start();
        service.flush();

        assertThat(lastBatch()).filteredOn(c -> c.source().equals("gemini")).containsExactly(removedExchange.completed());
        assertThat(service.current(BTC_USD, "gemini")).isEmpty();
    }

    @Test
    void finalFlushOnStop() {
        service.start();
        trade("kraken", "100");

        service.stop();

        assertThat(lastBatch()).extracting(Candle::source).contains("kraken", CandleService.INDEX);
    }

    private static void assertFlat(Candle candle, String price) {
        assertThat(List.of(candle.open(), candle.high(), candle.low(), candle.close()))
                .as("flat candle at %s", price)
                .containsOnly(d(price));
        assertThat(candle.updates()).isZero();
    }

    private void trade(String exchange, String price) {
        book.onTrade(exchange, BTC_USD, new BigDecimal(price));
    }

    private Candle current(String source, CandlePeriod period) {
        return service.current(BTC_USD, source).stream()
                .filter(c -> c.period() == period).findFirst().orElseThrow();
    }

    private List<Candle> lastBatch() {
        return written.getLast();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Candle>> captor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static Decimal d(String value) {
        return Decimal.parse(value);
    }
}
