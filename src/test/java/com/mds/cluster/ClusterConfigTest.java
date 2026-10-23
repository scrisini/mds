package com.mds.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.mds.config.MdsProperties;
import com.mds.service.CandleSerializers;
import org.junit.jupiter.api.Test;

/** Nothing the member does may reach outside the private network or bypass the JSON logs. */
class ClusterConfigTest {

    private static final MdsProperties.Rebalance REBALANCE = new MdsProperties.Rebalance(true, Duration.ofSeconds(10),
            0.2, 20);

    @Test
    void nothingCallsOutAndLogsGoThroughSlf4j() {
        Config config = config(List.of(), "");

        assertThat(config.getProperty("hazelcast.phone.home.enabled")).isEqualTo("false");
        assertThat(config.getProperty("hazelcast.logging.type")).isEqualTo("slf4j");
        assertThat(config.getProperty("hazelcast.shutdownhook.enabled")).isEqualTo("false");
        JoinConfig join = config.getNetworkConfig().getJoin();
        assertThat(join.getAutoDetectionConfig().isEnabled()).as("cloud metadata probing").isFalse();
        assertThat(join.getMulticastConfig().isEnabled()).isFalse();
        assertThat(join.getTcpIpConfig().isEnabled()).isFalse();
        assertThat(join.getKubernetesConfig().isEnabled()).isFalse();
        assertThat(config.getSerializationConfig().getJavaSerializationFilterConfig()).isNotNull();
    }

    @Test
    void joinsTheConfiguredMembersOrTheKubernetesService() {
        JoinConfig tcp = config(List.of("10.0.0.2", "10.0.0.3:5702"), "").getNetworkConfig().getJoin();
        assertThat(tcp.getTcpIpConfig().isEnabled()).isTrue();
        assertThat(tcp.getTcpIpConfig().getMembers()).containsExactly("10.0.0.2", "10.0.0.3:5702");

        JoinConfig kubernetes = config(List.of(), "mds-hz.prod.svc.cluster.local").getNetworkConfig().getJoin();
        assertThat(kubernetes.getKubernetesConfig().isEnabled()).isTrue();
        assertThat(kubernetes.getKubernetesConfig().getProperty("service-dns")).isEqualTo("mds-hz.prod.svc.cluster.local");
        assertThat(kubernetes.getTcpIpConfig().isEnabled()).isFalse();
    }

    private static Config config(List<String> members, String kubernetesServiceDns) {
        return ClusterConfig.hazelcastConfig(new MdsProperties.Cluster("mds", 5701, members, kubernetesServiceDns,
                REBALANCE), CandleSerializers.all());
    }
}
