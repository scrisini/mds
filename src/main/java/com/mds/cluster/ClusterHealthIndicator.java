package com.mds.cluster;

import java.util.List;

import com.hazelcast.core.HazelcastInstance;
import com.mds.config.MdsProperties;
import com.mds.model.MarketPair;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code cluster} in {@code /actuator/health}: up while this member runs, with the member count and
 * the pairs this instance owns (builds candles for and archives).
 */
@Component
public class ClusterHealthIndicator implements HealthIndicator {

    private final HazelcastInstance hazelcast;
    private final Cluster cluster;
    private final List<MarketPair> pairs;

    public ClusterHealthIndicator(HazelcastInstance hazelcast, Cluster cluster, MdsProperties properties) {
        this.hazelcast = hazelcast;
        this.cluster = cluster;
        this.pairs = properties.pairs().stream().map(MarketPair::parse).toList();
    }

    @Override
    public Health health() {
        if (!hazelcast.getLifecycleService().isRunning()) {
            return Health.down().withDetail("reason", "cluster member stopped").build();
        }
        return Health.up()
                .withDetail("name", hazelcast.getConfig().getClusterName())
                .withDetail("members", cluster.memberCount())
                .withDetail("ownedPairs", pairs.stream().filter(cluster::owns).map(MarketPair::symbol).toList())
                .build();
    }
}
