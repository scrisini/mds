package com.mds.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.mds.exchange.MarketStreamManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * With {@code MDS_PROMETHEUS_ENABLED}, {@code /actuator/prometheus} serves the meters that the
 * Grafana dashboards in {@code config/grafana} query, under their Prometheus names.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mds.pairs=BTC/USDT",
        "mds.archive.enabled=false",
        "mds.influx.url=http://127.0.0.1:1",
        "management.prometheus.metrics.export.enabled=true"})
class PrometheusEndpointTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private MarketStreamManager streams;

    @Test
    void servesTheMetersTheDashboardsUse() throws Exception {
        HttpResponse<String> response = get(port, "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("jvm_memory_used_bytes{")
                .contains("process_uptime_seconds{")
                .contains("mds_cluster_members{")
                .contains("application=\"mds\"");
    }

    static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
