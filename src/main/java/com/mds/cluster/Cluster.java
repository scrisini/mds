package com.mds.cluster;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.hazelcast.cluster.Member;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.cluster.MembershipListener;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.partition.Partition;
import com.mds.config.MdsProperties;
import com.mds.logging.LogEvent;
import com.mds.model.MarketPair;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * This instance's view of the mds cluster: which pairs it owns, who the members are, and the shared
 * maps. Hides Hazelcast from the services.
 *
 * <p>Every pair is owned by exactly one member: the owner of the Hazelcast partition of the pair's
 * symbol. The owner builds the pair's candles and archives its prices; other members only stream
 * it to their own subscribers. When a member leaves, its partitions, with their data, move to the
 * members that held the backups.
 *
 * <p>Settings that change what shared data means ({@code mds.decimal.*}, which candles are stored
 * in, and {@code mds.ohlc.zone}, where periods start) must be the same on every member. The first
 * member records them; a member that disagrees refuses to start.
 *
 * <p>Metrics: {@code mds.cluster.members} gauge.
 */
@Slf4j
public class Cluster {

    public static final String CANDLES = "mds.candles";
    public static final String COMPLETED_CANDLES = "mds.candles.completed";
    /** Pairs whose candles are in the cluster, keyed by the pair's symbol so the entry lives with them. */
    public static final String LOADED_PAIRS = "mds.candles.loaded";
    public static final String STREAM_LOAD = "mds.stream.load";
    static final String SETTINGS = "mds.cluster.settings";

    private final HazelcastInstance hazelcast;
    private final Map<MarketPair, Partition> partitions = new ConcurrentHashMap<>();

    public Cluster(HazelcastInstance hazelcast, MdsProperties properties, MeterRegistry registry) {
        this.hazelcast = hazelcast;
        verifySettings(sharedSettings(properties));
        hazelcast.getCluster().addMembershipListener(new MembershipLogging());
        Gauge.builder("mds.cluster.members", this, Cluster::memberCount)
                .description("Instances in the mds cluster, this one included")
                .register(registry);
        LogEvent.CLUSTER_JOINED.at(log).with("cluster", properties.cluster().name())
                .with("members", memberCount()).with("address", localMember().getAddress()).log();
    }

    /** Whether this member owns the pair: builds its candles and archives its prices. */
    public boolean owns(MarketPair pair) {
        // the Partition is a live view: its owner changes as members join and leave
        Member owner = partitions.computeIfAbsent(pair, p -> hazelcast.getPartitionService().getPartition(p.toString()))
                .getOwner();
        return owner != null && owner.localMember();
    }

    /** The member that has been in the cluster longest; it recovers state when the cluster starts. */
    public boolean isOldestMember() {
        return hazelcast.getCluster().getMembers().iterator().next().localMember();
    }

    public int memberCount() {
        return hazelcast.getCluster().getMembers().size();
    }

    public String localMemberId() {
        return localMember().getUuid().toString();
    }

    /** A cluster-wide map; entries are partitioned across the members. */
    public <K, V> IMap<K, V> map(String name) {
        return hazelcast.getMap(name);
    }

    /** The values every member must share, e.g. {@code decimal=8 HALF_EVEN, zone=UTC}. */
    static String sharedSettings(MdsProperties properties) {
        return "decimal=%d %s, zone=%s".formatted(properties.decimal().scale(), properties.decimal().rounding(),
                properties.ohlc().zone());
    }

    private void verifySettings(String local) {
        IMap<String, String> settings = map(SETTINGS);
        String cluster = settings.putIfAbsent("shared", local);
        if (cluster != null && !cluster.equals(local)) {
            LogEvent.CLUSTER_SETTINGS_MISMATCH.at(log).with("local", local).with("cluster_settings", cluster).log();
            throw new IllegalStateException(("This instance has %s but the mds cluster has %s; stop every "
                    + "instance to change these settings").formatted(local, cluster));
        }
    }

    private Member localMember() {
        return hazelcast.getCluster().getLocalMember();
    }

    private final class MembershipLogging implements MembershipListener {

        @Override
        public void memberAdded(MembershipEvent event) {
            LogEvent.CLUSTER_MEMBER_JOINED.at(log).with("member", event.getMember().getAddress())
                    .with("members", event.getMembers().size()).log();
        }

        @Override
        public void memberRemoved(MembershipEvent event) {
            LogEvent.CLUSTER_MEMBER_LEFT.at(log).with("member", event.getMember().getAddress())
                    .with("members", event.getMembers().size()).log();
        }
    }
}
