package com.mds.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.hazelcast.core.HazelcastInstance;
import com.mds.config.MdsProperties;
import com.mds.exchange.MarketStreamManager;
import com.mds.math.Decimal;
import com.mds.model.AggregatedPrice;
import com.mds.model.Candle;
import com.mds.model.CandlePeriod;
import com.mds.model.MarketPair;
import com.mds.persistence.PriceRepository;
import com.mds.service.CandleKey;
import com.mds.service.CandleService;
import com.mds.service.PriceAggregator;
import com.mds.service.PriceArchiver;
import com.mds.service.PriceService;
import com.mds.service.QuoteBook;
import com.mds.support.MutableClock;
import com.mds.support.TestClusters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two members in one JVM, both streaming the same prices, as two instances connected to the same
 * exchanges would. Each pair's candles are built and written by its owner only, and survive the
 * owner crashing.
 */
class ClusterCandlesTest {

    private static final List<MarketPair> PAIRS = List.of("BTC/USD", "ETH/USD", "SOL/USD", "XRP/USD", "ADA/USD")
            .stream().map(MarketPair::parse).toList();
    private static final Instant MORNING = Instant.parse("2026-10-21T09:00:00Z");
    private static final Instant TODAY = Instant.parse("2026-10-21T00:00:00Z");

    private final MutableClock clock = new MutableClock(MORNING);
    private final MdsProperties properties = properties(TestClusters.local("mds-test-" + UUID.randomUUID()));
    private Instance first;
    private Instance second;

    @BeforeEach
    void startTwoMembers() {
        first = new Instance();
        second = new Instance();
        // joined, and every partition's backup in place
        await().atMost(Duration.ofSeconds(30)).until(() -> first.cluster.memberCount() == 2
                && first.member.getPartitionService().isClusterSafe());
        first.candles.start();
        second.candles.start();
    }

    @AfterEach
    void stop() {
        for (Instance instance : new Instance[] {first, second}) {
            if (instance != null && instance.member.getLifecycleService().isRunning()) {
                instance.candles.stop();
                instance.member.shutdown();
            }
        }
    }

    @Test
    void eachPairIsOwnedByExactlyOneMemberWhichAloneWritesItsCandles() {
        for (MarketPair pair : PAIRS) {
            assertThat(first.cluster.owns(pair)).as(pair.toString()).isNotEqualTo(second.cluster.owns(pair));
            // a pair's candle series live in the pair's partition
            assertThat(first.member.getPartitionService().getPartition(new CandleKey("kraken", pair, CandlePeriod.DAY))
                    .getOwner()).isEqualTo(first.member.getPartitionService().getPartition(pair.toString()).getOwner());
        }
        tradeOnBoth("kraken", "100");
        tradeOnBoth("kraken", "110");

        first.candles.scheduledFlush();
        second.candles.scheduledFlush();

        for (MarketPair pair : PAIRS) {
            Instance owner = first.cluster.owns(pair) ? first : second;
            Instance other = owner == first ? second : first;
            assertThat(owner.writtenFor(pair)).isNotEmpty();
            assertThat(other.writtenFor(pair)).isEmpty();
            Candle day = owner.writtenFor(pair).stream()
                    .filter(c -> c.source().equals("kraken") && c.period() == CandlePeriod.DAY).findFirst().orElseThrow();
            assertThat(day.updates()).as("each price counted once").isEqualTo(2);
        }
    }

    @Test
    void theSurvivorContinuesTheCandlesOfAMemberThatCrashed() {
        MarketPair pair = PAIRS.getFirst();
        Instance owner = first.cluster.owns(pair) ? first : second;
        Instance survivor = owner == first ? second : first;
        tradeOnBoth("kraken", "100");
        tradeOnBoth("kraken", "120");
        tradeOnBoth("kraken", "90");
        // the next day starts on the owner, completing yesterday's candle, which it never writes
        clock.set(Instant.parse("2026-10-22T00:00:01Z"));
        tradeOnBoth("kraken", "95");

        owner.member.getLifecycleService().terminate(); // a crash: no flush, no graceful hand-over
        await().atMost(Duration.ofSeconds(30))
                .until(() -> survivor.cluster.memberCount() == 1 && survivor.cluster.owns(pair));
        tradeOnBoth("kraken", "97");
        survivor.candles.scheduledFlush();

        List<Candle> kraken = survivor.writtenFor(pair).stream()
                .filter(c -> c.source().equals("kraken") && c.period() == CandlePeriod.DAY).toList();
        assertThat(kraken).anySatisfy(yesterday -> {
            assertThat(yesterday.start()).isEqualTo(TODAY);
            assertThat(yesterday.complete()).isTrue();
            assertThat(List.of(yesterday.open(), yesterday.high(), yesterday.low(), yesterday.close()))
                    .containsExactly(d("100"), d("120"), d("90"), d("90"));
        });
        assertThat(kraken).anySatisfy(today -> {
            assertThat(today.start()).isEqualTo(TODAY.plus(Duration.ofDays(1)));
            assertThat(List.of(today.open(), today.close())).containsExactly(d("95"), d("97"));
            assertThat(today.updates()).isEqualTo(2);
        });
    }

    @Test
    void eachPairIsArchivedByItsOwnerOnly() {
        tradeOnBoth("kraken", "100");

        first.archiver.archive();
        second.archiver.archive();

        // together they archive every pair, each once; which member gets which is up to Hazelcast
        List<String> archived = new java.util.ArrayList<>();
        first.archived.forEach(a -> archived.add(a.pair()));
        second.archived.forEach(a -> archived.add(a.pair()));
        assertThat(archived).containsExactlyInAnyOrderElementsOf(PAIRS.stream().map(MarketPair::toString).toList());
    }

    @Test
    void aMemberWithDifferentDecimalSettingsRefusesToJoin() {
        MdsProperties scale6 = new MdsProperties(properties.exchanges(), properties.pairs(),
                properties.connectionTimeout(), properties.reconnectDelay(), properties.staleAfter(),
                properties.archive(), properties.stream(), new MdsProperties.DecimalSettings(6, RoundingMode.HALF_EVEN),
                properties.ohlc(), properties.socksProxy(), properties.cluster());
        HazelcastInstance third = TestClusters.member(properties.cluster());
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Cluster(third, scale6, new SimpleMeterRegistry()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("decimal=6").hasMessageContaining("decimal=8");
        } finally {
            third.shutdown();
        }
    }

    private void tradeOnBoth(String exchange, String price) {
        for (MarketPair pair : PAIRS) {
            for (Instance instance : List.of(first, second)) {
                if (instance.member.getLifecycleService().isRunning()) {
                    instance.book.onTrade(exchange, pair, new BigDecimal(price));
                }
            }
        }
    }

    private static MdsProperties properties(MdsProperties.Cluster cluster) {
        return new MdsProperties(List.of("kraken"), PAIRS.stream().map(MarketPair::toString).toList(),
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofDays(400),
                new MdsProperties.Archive(true, Duration.ofSeconds(5)),
                new MdsProperties.Stream(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(60),
                        Duration.ofMinutes(30), 10, true, List.of("*"), Duration.ofSeconds(30)),
                new MdsProperties.DecimalSettings(8, RoundingMode.HALF_EVEN),
                new MdsProperties.Ohlc(ZoneOffset.UTC, Duration.ofSeconds(10)),
                new MdsProperties.SocksProxy("", 1080), cluster);
    }

    private static Decimal d(String value) {
        return Decimal.parse(value);
    }

    /** One mds instance: its own member, quote book and InfluxDB, recording what it writes. */
    private final class Instance {

        final HazelcastInstance member = TestClusters.member(properties.cluster());
        final Cluster cluster = new Cluster(member, properties, new SimpleMeterRegistry());
        final QuoteBook book = new QuoteBook(clock);
        final PriceRepository repository = mock(PriceRepository.class);
        final List<Candle> written = new CopyOnWriteArrayList<>();
        final List<AggregatedPrice> archived = new CopyOnWriteArrayList<>();
        final CandleService candles;
        final PriceArchiver archiver;

        Instance() {
            MarketStreamManager streams = mock(MarketStreamManager.class);
            when(streams.exchangesFor(any())).thenReturn(List.of("kraken"));
            PriceService prices = new PriceService(streams, book, new PriceAggregator(), properties, clock);
            when(repository.findLatestCandles(any(), any())).thenReturn(List.of());
            doAnswer(inv -> written.addAll(inv.getArgument(0))).when(repository).saveCandles(anyList());
            doAnswer(inv -> archived.addAll(inv.getArgument(0))).when(repository).save(anyList());
            candles = new CandleService(book, prices, repository, cluster, properties, clock, Schedulers.trampoline());
            archiver = new PriceArchiver(prices, repository, cluster);
        }

        List<Candle> writtenFor(MarketPair pair) {
            return written.stream().filter(c -> c.pair().equals(pair.toString())).toList();
        }
    }
}
