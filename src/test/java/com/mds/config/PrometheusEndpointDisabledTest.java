package com.mds.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mds.exchange.MarketStreamManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Without {@code MDS_PROMETHEUS_ENABLED}, there is no {@code /actuator/prometheus}. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mds.pairs=BTC/USDT",
        "mds.archive.enabled=false",
        "mds.influx.url=http://127.0.0.1:1"})
class PrometheusEndpointDisabledTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private MarketStreamManager streams;

    @Test
    void isOffByDefault() throws Exception {
        assertThat(PrometheusEndpointTest.get(port, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }
}
