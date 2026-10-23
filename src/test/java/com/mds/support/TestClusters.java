package com.mds.support;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.mds.cluster.ClusterConfig;
import com.mds.config.MdsProperties;
import com.mds.service.CandleSerializers;

/** Cluster settings and members for tests: unique names, ports away from a local mds (5701). */
public final class TestClusters {

    public static final int PORT = 5901;

    private TestClusters() {
    }

    /** A cluster of one with a name nothing else uses. */
    public static MdsProperties.Cluster standalone() {
        return settings(List.of());
    }

    /**
     * Members that find each other on 127.0.0.1 under one shared name. Without a port, Hazelcast tries
     * {@link #PORT} and the next two, enough for three members.
     */
    public static MdsProperties.Cluster local(String name) {
        return new MdsProperties.Cluster(name, PORT, List.of("127.0.0.1"), "", rebalance());
    }

    public static HazelcastInstance member(MdsProperties.Cluster cluster) {
        return Hazelcast.newHazelcastInstance(ClusterConfig.hazelcastConfig(cluster, CandleSerializers.all()));
    }

    private static MdsProperties.Cluster settings(List<String> members) {
        return new MdsProperties.Cluster("mds-test-" + UUID.randomUUID(), PORT, members, "", rebalance());
    }

    private static MdsProperties.Rebalance rebalance() {
        return new MdsProperties.Rebalance(true, Duration.ofSeconds(10), 0.2, 20);
    }
}
