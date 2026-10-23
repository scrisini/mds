package com.mds.service;

import java.util.concurrent.TimeUnit;

import com.hazelcast.map.IMap;
import com.mds.cluster.Cluster;
import com.mds.config.MdsProperties;
import com.mds.logging.LogEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spreads real-time subscribers across the cluster. Long-lived SSE and WebSocket connections stay
 * where the load balancer first sent them, so after a scale-out or a restart some instances carry
 * far more than others. Every {@code mds.cluster.rebalance.interval} each instance publishes its
 * subscription count to {@value Cluster#STREAM_LOAD} and, if it exceeds the fair share
 * ({@code total / instances}) by more than {@code tolerance}, sheds the excess down to the fair share,
 * at most {@code max-per-round} at a time ({@link PriceStreamService#shed}). Their clients reconnect
 * through the load balancer, which should use least-connections balancing, to quieter instances.
 *
 * <p>After shedding, an instance skips the next {@value #COOL_DOWN_ROUNDS} rounds, so the clients it
 * closed have reconnected and are counted before it decides again; otherwise the totals would still
 * be missing them and it would shed again. A member's count expires after three intervals, so one
 * that has gone no longer counts.
 *
 * <p>Metrics: {@code mds.stream.rebalanced} counter of subscriptions shed.
 */
@Slf4j
@Component
public class StreamRebalancer {

    static final int COOL_DOWN_ROUNDS = 2;

    private final PriceStreamService streams;
    private final Cluster cluster;
    private final IMap<String, Integer> load;
    private final MdsProperties.Rebalance settings;
    private final Counter rebalanced;
    private int coolDown; // rounds still to skip; only the scheduler thread touches it

    public StreamRebalancer(PriceStreamService streams, Cluster cluster, MdsProperties properties,
                            MeterRegistry registry) {
        this.streams = streams;
        this.cluster = cluster;
        this.load = cluster.map(Cluster.STREAM_LOAD);
        this.settings = properties.cluster().rebalance();
        this.rebalanced = Counter.builder("mds.stream.rebalanced")
                .description("Real-time subscriptions closed so their clients reconnect to a less loaded instance")
                .register(registry);
    }

    @Scheduled(initialDelayString = "${mds.cluster.rebalance.interval:10s}",
            fixedDelayString = "${mds.cluster.rebalance.interval:10s}")
    public void rebalance() {
        int local = streams.subscribers();
        load.set(cluster.localMemberId(), local, 3 * settings.interval().toMillis(), TimeUnit.MILLISECONDS);
        if (!settings.enabled()) {
            return;
        }
        if (coolDown > 0) {
            coolDown--;
            return;
        }
        int members = cluster.memberCount();
        int total = load.values().stream().mapToInt(Integer::intValue).sum();
        int excess = Math.min(excess(local, total, members, settings.tolerance()), settings.maxPerRound());
        if (excess > 0) {
            int closed = streams.shed(excess);
            rebalanced.increment(closed);
            coolDown = COOL_DOWN_ROUNDS;
            LogEvent.SUBSCRIBERS_REBALANCED.at(log).with("local", local).with("total", total)
                    .with("members", members).with("fair_share", fairShare(total, members)).with("closed", closed).log();
        }
    }

    /**
     * Subscriptions to shed: down to the fair share, once {@code local} exceeds it by more than
     * {@code tolerance}; 0 otherwise, and always for a cluster of one.
     */
    static int excess(int local, int total, int members, double tolerance) {
        if (members <= 1) {
            return 0;
        }
        int fairShare = fairShare(total, members);
        return local > Math.ceil(fairShare * (1 + tolerance)) ? local - fairShare : 0;
    }

    private static int fairShare(int total, int members) {
        return (total + members - 1) / members;
    }
}
