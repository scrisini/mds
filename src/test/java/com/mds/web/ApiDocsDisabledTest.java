package com.mds.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mds.exchange.MarketStreamManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** With {@code MDS_API_DOCS_ENABLED=false}, as in production, neither the description nor the UI exists. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mds.pairs=BTC/USDT",
        "mds.archive.enabled=false",
        "mds.influx.url=http://127.0.0.1:1",
        "MDS_API_DOCS_ENABLED=false"})
class ApiDocsDisabledTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private MarketStreamManager streams;

    @Test
    void isOffWhenDisabled() throws Exception {
        assertThat(ApiDocsTest.get(port, "/v3/api-docs").statusCode()).isEqualTo(404);
        assertThat(ApiDocsTest.get(port, "/swagger-ui/index.html").statusCode()).isEqualTo(404);
        assertThat(ApiDocsTest.get(port, "/swagger-ui.html").statusCode()).isEqualTo(404);
    }
}
