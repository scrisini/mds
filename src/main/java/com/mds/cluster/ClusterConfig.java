package com.mds.cluster;

import java.util.Collection;
import java.util.List;

import com.hazelcast.config.Config;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.nio.serialization.compact.CompactSerializer;
import com.mds.config.MdsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The embedded Hazelcast member. Every instance is one; with no peers configured it is a cluster of
 * one. Settings that would reach outside the private network are off: phone-home (usage statistics
 * to hazelcast.com), cloud auto-detection (metadata endpoints) and multicast. Only TCP to the
 * configured members, or the Kubernetes DNS records, is used.
 */
@Configuration(proxyBeanMethods = false)
public class ClusterConfig {

    @Bean(destroyMethod = "shutdown")
    public HazelcastInstance hazelcastInstance(MdsProperties properties, List<CompactSerializer<?>> serializers) {
        return Hazelcast.newHazelcastInstance(hazelcastConfig(properties.cluster(), serializers));
    }

    @Bean
    public Cluster cluster(HazelcastInstance hazelcast, MdsProperties properties, MeterRegistry registry) {
        return new Cluster(hazelcast, properties, registry);
    }

    /** The member configuration; public for tests that start several members in one JVM. */
    public static Config hazelcastConfig(MdsProperties.Cluster cluster, Collection<CompactSerializer<?>> serializers) {
        Config config = new Config();
        config.setClusterName(cluster.name());
        // nothing leaves the private network, and logs go through SLF4J (JSON in production)
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.logging.type", "slf4j");
        // Spring shuts the member down after the final candle flush, not the JVM hook before it
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        // after a split (network trouble, members restarting at once) the halves both own every pair and
        // write the same candles until they merge: look for a merge after 30 s rather than the default 300 s
        config.setProperty("hazelcast.merge.first.run.delay.seconds", "30");
        config.setProperty("hazelcast.merge.next.run.delay.seconds", "30");

        config.getNetworkConfig().setPort(cluster.port()).setPortAutoIncrement(true);
        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        if (!cluster.members().isEmpty()) {
            join.getTcpIpConfig().setEnabled(true).setMembers(cluster.members());
        } else if (!cluster.kubernetesServiceDns().isEmpty()) {
            join.getKubernetesConfig().setEnabled(true).setProperty("service-dns", cluster.kubernetesServiceDns());
        }

        // no Java serialization from the network beyond the JDK and Hazelcast basics; ours is compact
        config.getSerializationConfig().setJavaSerializationFilterConfig(new JavaSerializationFilterConfig());
        serializers.forEach(config.getSerializationConfig().getCompactSerializationConfig()::addSerializer);

        // candles change on every tick through entry processors: keep them as objects, one sync backup
        config.addMapConfig(new MapConfig(Cluster.CANDLES).setInMemoryFormat(InMemoryFormat.OBJECT).setBackupCount(1));
        config.addMapConfig(new MapConfig(Cluster.COMPLETED_CANDLES).setBackupCount(1));
        config.addMapConfig(new MapConfig(Cluster.LOADED_PAIRS).setBackupCount(1));
        config.addMapConfig(new MapConfig(Cluster.STREAM_LOAD).setBackupCount(1));
        config.addMapConfig(new MapConfig(Cluster.SETTINGS).setBackupCount(1));
        return config;
    }
}
