package com.mds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.hazelcast.map.IMap;
import com.mds.cluster.Cluster;
import com.mds.config.MdsProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamRebalancerTest {

    private final PriceStreamService streams = mock(PriceStreamService.class);
    private final Cluster cluster = mock(Cluster.class);
    @SuppressWarnings("unchecked")
    private final IMap<String, Integer> load = mock(IMap.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        when(cluster.<String, Integer>map(Cluster.STREAM_LOAD)).thenReturn(load);
        when(cluster.localMemberId()).thenReturn("me");
    }

    @Test
    void excessIsDownToTheFairShareOnceAboveTheTolerance() {
        // 100 across 2: fair share 50, shed above 60
        assertThat(StreamRebalancer.excess(70, 100, 2, 0.2)).isEqualTo(20);
        assertThat(StreamRebalancer.excess(60, 100, 2, 0.2)).isZero();
        assertThat(StreamRebalancer.excess(61, 101, 2, 0.2)).isZero(); // fair share rounds up to 51
        assertThat(StreamRebalancer.excess(0, 0, 3, 0.2)).isZero();
        assertThat(StreamRebalancer.excess(500, 500, 1, 0.2)).as("a cluster of one").isZero();
    }

    @Test
    void publishesItsLoadAndShedsAtMostMaxPerRound() {
        when(streams.subscribers()).thenReturn(90);
        when(load.values()).thenReturn(List.of(90, 10));
        when(cluster.memberCount()).thenReturn(2);
        when(streams.shed(anyInt())).thenAnswer(inv -> inv.getArgument(0));

        rebalancer(true, 20).rebalance();

        // 30s: three 10s intervals, so a member that's gone stops counting
        verify(load).set("me", 90, 30_000, TimeUnit.MILLISECONDS);
        verify(streams).shed(20); // 40 above the fair share of 50, capped at 20
        assertThat(registry.get("mds.stream.rebalanced").counter().count()).isEqualTo(20);
    }

    @Test
    void waitsTwoRoundsAfterSheddingSoReconnectedClientsAreCounted() {
        when(streams.subscribers()).thenReturn(90);
        when(load.values()).thenReturn(List.of(90, 10));
        when(cluster.memberCount()).thenReturn(2);
        when(streams.shed(anyInt())).thenAnswer(inv -> inv.getArgument(0));
        StreamRebalancer rebalancer = rebalancer(true, 20);

        rebalancer.rebalance(); // sheds
        rebalancer.rebalance(); // cooling down
        rebalancer.rebalance(); // cooling down
        verify(streams, times(1)).shed(anyInt());

        rebalancer.rebalance(); // decides again
        verify(streams, times(2)).shed(anyInt());
        verify(load, times(4)).set(eq("me"), eq(90), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void whenDisabledItStillPublishesItsLoadButShedsNothing() {
        when(streams.subscribers()).thenReturn(90);

        rebalancer(false, 20).rebalance();

        verify(load).set(eq("me"), eq(90), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(streams, never()).shed(anyInt());
    }

    private StreamRebalancer rebalancer(boolean enabled, int maxPerRound) {
        MdsProperties.Cluster settings = new MdsProperties.Cluster("mds", 5701, List.of(), "",
                new MdsProperties.Rebalance(enabled, Duration.ofSeconds(10), 0.2, maxPerRound));
        MdsProperties properties = new MdsProperties(List.of("kraken"), List.of("BTC/USD"), Duration.ofSeconds(10),
                Duration.ofSeconds(5), Duration.ofSeconds(60), new MdsProperties.Archive(false, Duration.ofSeconds(5)),
                new MdsProperties.Stream(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(60),
                        Duration.ofMinutes(30), 10, true, List.of("*")),
                new MdsProperties.DecimalSettings(8, RoundingMode.HALF_EVEN),
                new MdsProperties.Ohlc(ZoneOffset.UTC, Duration.ofSeconds(10)),
                new MdsProperties.SocksProxy("", 1080), settings);
        return new StreamRebalancer(streams, cluster, properties, registry);
    }
}
