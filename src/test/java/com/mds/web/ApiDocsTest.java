package com.mds.web;

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
 * By default the REST API's OpenAPI description is served at {@code /v3/api-docs}, with every
 * REST controller and nothing from {@code /actuator}, and Swagger UI at {@code /swagger-ui.html}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mds.pairs=BTC/USDT",
        "mds.archive.enabled=false",
        "mds.influx.url=http://127.0.0.1:1"})
class ApiDocsTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private MarketStreamManager streams;

    @Test
    void describesTheRestApi() throws Exception {
        HttpResponse<String> response = get(port, "/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"title\":\"mds market data API\"")
                .contains("\"/api/v1/prices/{pair}\"", "\"/api/v1/candles/{pair}\"", "\"/api/v1/history/{pair}\"",
                        "\"/api/v1/stream/prices/{pair}\"", "\"/api/v1/exchanges\"")
                .contains("\"name\":\"Candles\"", "hour, day, week, month or year")
                .as("prices are numbers, not Decimal's getters").contains("\"mean\":{\"type\":\"number\"")
                .doesNotContain("\"Decimal\"", "SseEmitter")
                .contains("\"default\":{\"description\":\"Error: an RFC 9457 problem", "\"application/problem+json\"",
                        "\"requestId\"")
                .contains("\"200\":{\"description\":\"OK\",\"content\":{\"application/json\"")
                .doesNotContain("/actuator");
    }

    @Test
    void servesSwaggerUi() throws Exception {
        HttpResponse<String> response = get(port, "/swagger-ui/index.html");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Swagger UI");
        assertThat(get(port, "/swagger-ui.html").statusCode()).as("redirects to the UI").isEqualTo(302);
    }

    static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
