package com.mds.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.mds.config.WebSocketConfig;
import com.mds.exchange.MarketStreamManager;
import com.mds.model.MarketPair;
import com.mds.service.QuoteBook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A real WebSocket client against the running app; exchanges and InfluxDB are not needed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mds.pairs=BTC/USDT,ETH/USDT",
        "mds.archive.enabled=false",
        "mds.influx.url=http://127.0.0.1:1"})
class PriceWebSocketIntegrationTest {

    private static final MarketPair BTC_USDT = MarketPair.parse("BTC-USDT");
    private static final MarketPair ETH_USDT = MarketPair.parse("ETH-USDT");

    @LocalServerPort
    private int port;

    @Autowired
    private QuoteBook quoteBook;

    @Autowired
    private PriceWebSocketHandler handler;

    @MockitoBean
    private MarketStreamManager streams;

    private final JsonMapper json = JsonMapper.builder().build();
    private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    private WebSocketSession client;

    @BeforeEach
    void connect() throws Exception {
        when(streams.exchangesFor(any())).thenReturn(List.of("kraken"));
        client = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.add(json.readTree(message.getPayload()));
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + WebSocketConfig.PRICES_PATH))
                .get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void disconnect() throws Exception {
        if (client.isOpen()) {
            client.close();
        }
    }

    @Test
    void streamsEachPairAtItsOwnRateOverOneSocket() throws Exception {
        trade(BTC_USDT, 100);
        trade(ETH_USDT, 2000);
        send("{\"action\":\"subscribe\",\"pair\":\"BTC-USDT\",\"rate\":2}");
        send("{\"action\":\"subscribe\",\"pair\":\"ETH-USDT\",\"rate\":\"unlimited\"}");
        awaitType("subscribed", 2);
        received.clear();

        // both pairs update every 50ms for 2 seconds: 40 changes each
        for (int tick = 1; tick <= 40; tick++) {
            trade(BTC_USDT, 100 + tick);
            trade(ETH_USDT, 2000 + tick);
            Thread.sleep(50);
        }
        Thread.sleep(600); // let the last throttle window close

        List<JsonNode> messages = List.copyOf(received);
        long btc = count(messages, "BTC-USDT");
        long eth = count(messages, "ETH-USDT");
        // rate=2 over ~2.6s: about 5; unlimited: (nearly) every one of the 40 changes
        assertThat(btc).as("BTC-USDT at 2/s").isBetween(3L, 7L);
        assertThat(eth).as("ETH-USDT unlimited").isGreaterThanOrEqualTo(30L);
        assertThat(lastMean(messages, "BTC-USDT")).isEqualTo("140");
        assertThat(lastMean(messages, "ETH-USDT")).isEqualTo("2040");
    }

    @Test
    void unsubscribeAndErrorsKeepTheSocketUsable() throws Exception {
        send("{\"action\":\"subscribe\",\"pair\":\"BTC-USDT\",\"rate\":0}");
        assertThat(awaitType("error", 1).getFirst().get("status").asInt()).isEqualTo(400);

        send("{\"action\":\"subscribe\",\"pair\":\"BTC-USDT\",\"rate\":1}");
        awaitType("subscribed", 1);
        send("{\"action\":\"unsubscribe\",\"pair\":\"BTC-USDT\"}");
        awaitType("unsubscribed", 1);
        assertThat(client.isOpen()).isTrue();
    }

    @Test
    void closingTheSocketReleasesItsSubscriptions() throws Exception {
        send("{\"action\":\"subscribe\",\"pair\":\"BTC-USDT\",\"rate\":1}");
        send("{\"action\":\"subscribe\",\"pair\":\"ETH-USDT\",\"rate\":1}");
        awaitType("subscribed", 2);
        assertThat(handler.openSubscriptions()).isEqualTo(2);

        client.close(CloseStatus.NORMAL);

        await().atMost(Duration.ofSeconds(5)).until(() -> handler.openSubscriptions() == 0);
    }

    private void trade(MarketPair pair, int price) {
        quoteBook.onTrade("kraken", pair, BigDecimal.valueOf(price));
    }

    private void send(String payload) throws Exception {
        client.sendMessage(new TextMessage(payload));
    }

    private List<JsonNode> awaitType(String type, int count) {
        await().atMost(Duration.ofSeconds(5)).until(() -> received.stream()
                .filter(m -> type.equals(m.get("type").asString())).count() >= count);
        return received.stream().filter(m -> type.equals(m.get("type").asString())).toList();
    }

    private static long count(List<JsonNode> messages, String pair) {
        return messages.stream()
                .filter(m -> "price".equals(m.get("type").asString()) && pair.equals(m.get("pair").asString()))
                .count();
    }

    private static String lastMean(List<JsonNode> messages, String pair) {
        return messages.stream()
                .filter(m -> "price".equals(m.get("type").asString()) && pair.equals(m.get("pair").asString()))
                .reduce((a, b) -> b).orElseThrow()
                .get("data").get("mean").asString();
    }
}
