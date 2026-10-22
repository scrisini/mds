package com.mds.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.mds.config.MdsProperties;
import com.mds.exchange.MarketStreamManager;
import com.mds.model.MarketPair;
import com.mds.service.PriceAggregator;
import com.mds.service.PriceService;
import com.mds.service.PriceStreamService;
import com.mds.service.QuoteBook;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PriceWebSocketHandlerTest {

    private static final MarketPair BTC_USDT = MarketPair.parse("BTC-USDT");

    private final QuoteBook book = new QuoteBook(Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
    private final JsonMapper json = JsonMapper.builder().build();
    private final WebSocketSession session = mock(WebSocketSession.class);
    private PriceWebSocketHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        MdsProperties properties = new MdsProperties(List.of("kraken"), List.of("BTC/USDT", "ETH/USDT"),
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofDays(1),
                new MdsProperties.Archive(false, Duration.ofSeconds(5)),
                new MdsProperties.Stream(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(60),
                        Duration.ofMinutes(30), 10, true, List.of("*")),
                new MdsProperties.DecimalSettings(8, RoundingMode.HALF_EVEN),
                new MdsProperties.Ohlc(ZoneOffset.UTC, Duration.ofSeconds(10)),
                new MdsProperties.SocksProxy("", 1080));
        MarketStreamManager streams = mock(MarketStreamManager.class);
        when(streams.exchangesFor(any())).thenReturn(List.of("kraken"));
        PriceService prices = new PriceService(streams, book, new PriceAggregator(), properties,
                Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
        PriceStreamService streamService = new PriceStreamService(book, prices, properties, new SimpleMeterRegistry(),
                new TestScheduler(), Schedulers.trampoline());
        handler = new PriceWebSocketHandler(streamService, json);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);
    }

    @Test
    void subscribeAcknowledgesAndStreamsPrices() throws Exception {
        book.onTrade("kraken", BTC_USDT, new BigDecimal("100"));

        send("{\"action\":\"subscribe\",\"pair\":\"btc-usdt\",\"rate\":\"unlimited\"}");
        book.onTrade("kraken", BTC_USDT, new BigDecimal("101"));

        List<JsonNode> sent = sent();
        // the current price goes out as soon as the subscription opens, before the acknowledgement
        assertThat(sent).extracting(m -> m.get("type").asString()).containsExactly("price", "subscribed", "price");
        JsonNode ack = sent.get(1);
        assertThat(ack.get("pair").asString()).isEqualTo("BTC-USDT");
        assertThat(ack.get("rate").asString()).isEqualTo("unlimited");
        JsonNode last = sent.getLast();
        assertThat(last.get("seq").asLong()).isEqualTo(2);
        assertThat(last.get("data").get("mean").asString()).isEqualTo("101");
        assertThat(last.get("data").get("pair").asString()).isEqualTo("BTC/USDT");
    }

    @Test
    void acceptsNumericRatesAndThrottleIntervals() throws Exception {
        send("{\"action\":\"subscribe\",\"pair\":\"BTC-USDT\",\"rate\":3}");
        send("{\"action\":\"subscribe\",\"pair\":\"ETH-USDT\",\"throttle\":\"500ms\"}");

        assertThat(sent()).extracting(m -> m.get("rate").asString()).containsExactly("3/s", "2/s");
        assertThat(handler.openSubscriptions()).isEqualTo(2);
    }

    @Test
    void resubscribingReplacesTheRate() throws Exception {
        send("{\"action\":\"subscribe\",\"pair\":\"BTC-USDT\",\"rate\":1}");
        send("{\"action\":\"subscribe\",\"pair\":\"BTC-USDT\",\"rate\":5}");

        assertThat(handler.openSubscriptions()).isEqualTo(1);
        assertThat(sent().getLast().get("rate").asString()).isEqualTo("5/s");
    }

    @Test
    void unsubscribeStopsUpdates() throws Exception {
        send("{\"action\":\"subscribe\",\"pair\":\"BTC-USDT\",\"rate\":\"unlimited\"}");
        send("{\"action\":\"unsubscribe\",\"pair\":\"BTC-USDT\"}");
        book.onTrade("kraken", BTC_USDT, new BigDecimal("100"));

        assertThat(sent()).extracting(m -> m.get("type").asString()).containsExactly("subscribed", "unsubscribed");
        assertThat(handler.openSubscriptions()).isZero();
    }

    @Test
    void reportsBadCommandsWithoutClosingTheSocket() throws Exception {
        send("not json");
        send("{\"action\":\"dance\"}");
        send("{\"action\":\"subscribe\"}");
        send("{\"action\":\"subscribe\",\"pair\":\"BTC-USDT\",\"rate\":50}");
        send("{\"action\":\"subscribe\",\"pair\":\"DOGE-USDT\",\"rate\":1}");
        send("{\"action\":\"unsubscribe\",\"pair\":\"ETH-USDT\"}");

        List<JsonNode> sent = sent();
        assertThat(sent).allSatisfy(m -> assertThat(m.get("type").asString()).isEqualTo("error"));
        assertThat(sent).extracting(m -> m.get("status").asInt()).containsExactly(400, 400, 400, 400, 404, 404);
        assertThat(sent.get(3).get("message").asString()).contains("between 1 and 10");
        verify(session, org.mockito.Mockito.never()).close(any());
    }

    @Test
    void closingTheSocketEndsItsSubscriptions() throws Exception {
        send("{\"action\":\"subscribe\",\"pair\":\"BTC-USDT\",\"rate\":1}");
        send("{\"action\":\"subscribe\",\"pair\":\"ETH-USDT\",\"rate\":1}");

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(handler.openSubscriptions()).isZero();
        assertThat(handler.trackedSessions()).isZero();
    }

    private void send(String payload) throws Exception {
        handler.handleMessage(session, new TextMessage(payload));
    }

    @SuppressWarnings("unchecked")
    private List<JsonNode> sent() throws Exception {
        ArgumentCaptor<WebSocketMessage<?>> messages = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, atLeastOnce()).sendMessage(messages.capture());
        return messages.getAllValues().stream()
                .map(m -> json.readTree(((TextMessage) m).getPayload()))
                .toList();
    }
}
