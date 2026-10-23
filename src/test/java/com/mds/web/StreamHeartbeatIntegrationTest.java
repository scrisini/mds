package com.mds.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.mds.exchange.MarketStreamManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * An SSE stream on a pair with no prices still sends a heartbeat comment, so load balancers don't
 * close it as idle. Exchanges and InfluxDB are not needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mds.pairs=BTC/USDT",
        "mds.archive.enabled=false",
        "mds.influx.url=http://127.0.0.1:1",
        "mds.stream.heartbeat=200ms"})
class StreamHeartbeatIntegrationTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private MarketStreamManager streams;

    @Test
    void aQuietSseStreamGetsHeartbeatComments() throws Exception {
        when(streams.exchangesFor(any())).thenReturn(List.of("kraken"));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/stream/prices/BTC-USDT?rate=1"))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<InputStream> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());

        assertThat(response.statusCode()).isEqualTo(200);
        try (BufferedReader body = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            // no exchange has a price, so the only lines are heartbeats
            assertThat(body.readLine()).isEqualTo(":heartbeat");
            assertThat(body.readLine()).isEmpty();
            assertThat(body.readLine()).isEqualTo(":heartbeat");
        }
    }
}
