package com.mds.web;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.service.PairNotSubscribedException;
import com.mds.service.PriceStreamService;
import com.mds.service.StreamCapacityException;
import com.mds.service.StreamRate;
import com.mds.service.StreamSubscription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real-time prices over WebSocket. Clients send JSON commands; one socket can carry several pairs,
 * each at its own rate.
 *
 * <pre>
 * → {"action":"subscribe","pair":"BTC-USDT","rate":2}        rate: 1, 2, 3… per second, or "unlimited"
 * → {"action":"subscribe","pair":"ETH-USD","throttle":"500ms"} alternatively a minimum interval
 * → {"action":"unsubscribe","pair":"BTC-USDT"}
 * ← {"type":"subscribed","pair":"BTC-USDT","rate":"2/s"}
 * ← {"type":"price","pair":"BTC-USDT","seq":1,"data":{...aggregate...}}
 * ← {"type":"unsubscribed","pair":"BTC-USDT"}
 * ← {"type":"error","status":400,"message":"..."}             the socket stays open
 * </pre>
 *
 * Subscribing to a pair again replaces its rate. {@code seq} counts price messages per pair.
 * A client that stops reading is disconnected (close code 1008) once its send buffer is full,
 * rather than holding memory on the server.
 */
@Slf4j
@Component
public class PriceWebSocketHandler extends TextWebSocketHandler {

    static final int SEND_TIME_LIMIT_MS = 10_000;
    static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final PriceStreamService streams;
    private final JsonMapper json;
    private final Map<String, Map<MarketPair, StreamSubscription>> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> decorated = new ConcurrentHashMap<>();

    public PriceWebSocketHandler(PriceStreamService streams, JsonMapper json) {
        this.streams = streams;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        subscriptions.put(session.getId(), new ConcurrentHashMap<>());
        log.debug("WebSocket {} connected from {}", session.getId(), session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws IOException {
        WebSocketSession session = concurrent(raw);
        JsonNode command;
        try {
            command = json.readTree(message.getPayload());
        } catch (JacksonException e) {
            sendError(session, 400, "messages must be JSON, e.g. {\"action\":\"subscribe\",\"pair\":\"BTC-USDT\",\"rate\":2}");
            return;
        }
        String action = text(command, "action").orElse("");
        try {
            switch (action) {
                case "subscribe" -> subscribe(session, command);
                case "unsubscribe" -> unsubscribe(session, command);
                default -> sendError(session, 400, "action must be 'subscribe' or 'unsubscribe'");
            }
        } catch (IllegalArgumentException e) {
            sendError(session, 400, e.getMessage());
        } catch (PairNotSubscribedException e) {
            sendError(session, 404, e.getMessage());
        } catch (StreamCapacityException e) {
            sendError(session, 503, e.getMessage());
        }
    }

    private void subscribe(WebSocketSession session, JsonNode command) throws IOException {
        MarketPair pair = MarketPair.parse(text(command, "pair")
                .orElseThrow(() -> new IllegalArgumentException("subscribe needs a pair, e.g. \"pair\":\"BTC-USDT\"")));
        StreamRate rate = streams.resolveRate(text(command, "rate"),
                text(command, "throttle").map(DurationStyle::detectAndParse));
        Map<MarketPair, StreamSubscription> open = subscriptions.get(session.getId());
        if (open == null) {
            return; // closed meanwhile
        }
        Optional.ofNullable(open.remove(pair)).ifPresent(StreamSubscription::close);

        AtomicLong sequence = new AtomicLong();
        StreamSubscription subscription = streams.open(pair, rate, PriceStreamService.WEBSOCKET,
                price -> send(session, new PriceMessage("price", pair.symbol(), sequence.incrementAndGet(), price)),
                error -> closeQuietly(session, error));
        open.put(pair, subscription);
        send(session, new SubscribedMessage("subscribed", pair.symbol(), rate.toString()));
    }

    private void unsubscribe(WebSocketSession session, JsonNode command) throws IOException {
        MarketPair pair = MarketPair.parse(text(command, "pair")
                .orElseThrow(() -> new IllegalArgumentException("unsubscribe needs a pair")));
        Map<MarketPair, StreamSubscription> open = subscriptions.getOrDefault(session.getId(), Map.of());
        StreamSubscription subscription = open.remove(pair);
        if (subscription == null) {
            sendError(session, 404, "not subscribed to " + pair.symbol());
            return;
        }
        subscription.close();
        send(session, new UnsubscribedMessage("unsubscribed", pair.symbol()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Map<MarketPair, StreamSubscription> open = subscriptions.remove(session.getId());
        if (open != null) {
            open.values().forEach(StreamSubscription::close);
        }
        decorated.remove(session.getId());
        log.debug("WebSocket {} closed: {}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket {} transport error: {}", session.getId(), exception.getMessage());
    }

    /** Open subscriptions across all sessions (for tests and diagnostics). */
    int openSubscriptions() {
        return subscriptions.values().stream().mapToInt(Map::size).sum();
    }

    /** Sessions currently tracked (for tests). */
    int trackedSessions() {
        return Math.max(subscriptions.size(), decorated.size());
    }

    /** Thread-safe, bounded view of the session: sends come from several subscriptions at once. */
    private WebSocketSession concurrent(WebSocketSession session) {
        return decorated.computeIfAbsent(session.getId(), id -> new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
    }

    private void send(WebSocketSession session, Object message) throws IOException {
        session.sendMessage(new TextMessage(json.writeValueAsString(message)));
    }

    private void sendError(WebSocketSession session, int status, String message) throws IOException {
        send(session, new ErrorMessage("error", status, message));
    }

    private void closeQuietly(WebSocketSession session, Throwable cause) {
        log.debug("Closing WebSocket {}: {}", session.getId(), cause.getMessage());
        try {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("send failed or client too slow"));
        } catch (IOException e) {
            // already gone
        }
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        return Optional.of(value.isString() ? value.asString() : value.toString());
    }

    record SubscribedMessage(String type, String pair, String rate) {
    }

    record UnsubscribedMessage(String type, String pair) {
    }

    record PriceMessage(String type, String pair, long seq, AggregatedPrice data) {
    }

    record ErrorMessage(String type, int status, String message) {
    }
}
