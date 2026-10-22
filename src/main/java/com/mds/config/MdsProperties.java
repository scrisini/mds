package com.mds.config;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Aggregator settings bound from the {@code mds.*} namespace.
 *
 * @param exchanges         exchange identifiers to stream from (see {@link com.mds.exchange.SupportedExchange})
 * @param pairs             currency pairs subscribed on every exchange that lists them, e.g. {@code BTC/USDT}
 * @param connectionTimeout HTTP timeout for the metadata calls made while connecting to an exchange
 * @param reconnectDelay    wait before retrying a failed connection or a terminated stream
 * @param staleAfter        quotes older than this are left out of aggregates
 * @param archive           snapshot archiving settings
 * @param stream            client SSE subscription settings
 * @param decimal           precision of all prices and volumes
 * @param ohlc              OHLC candle settings
 * @param socksProxy        SOCKS5 proxy for all outgoing exchange traffic
 */
@ConfigurationProperties("mds")
public record MdsProperties(
        @DefaultValue({"binance", "kraken", "bitstamp", "kucoin", "bitfinex"}) List<String> exchanges,
        @DefaultValue({"BTC/USDT", "ETH/USDT"}) List<String> pairs,
        @DefaultValue("10s") Duration connectionTimeout,
        @DefaultValue("5s") Duration reconnectDelay,
        @DefaultValue("60s") Duration staleAfter,
        @DefaultValue Archive archive,
        @DefaultValue Stream stream,
        @DefaultValue DecimalSettings decimal,
        @DefaultValue Ohlc ohlc,
        @DefaultValue SocksProxy socksProxy) {

    /**
     * @param enabled  whether snapshots of the live quotes are written to InfluxDB
     * @param interval time between snapshots
     */
    public record Archive(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") Duration interval) {
    }

    /**
     * Real-time price subscriptions over Server-Sent Events and WebSocket.
     *
     * @param defaultThrottle  minimum time between events when the client doesn't ask for a rate
     * @param minThrottle      shortest interval a client may request; also caps {@code rate} (100ms = 10/s)
     * @param maxThrottle      longest interval a client may request
     * @param timeout          SSE connection lifetime, and WebSocket idle timeout
     * @param maxSubscribers   concurrent subscriptions across all clients (an SSE connection, or one
     *                         pair on a WebSocket)
     * @param allowUnthrottled whether clients may request {@code rate=unlimited}
     * @param allowedOrigins   browser origins allowed to open WebSockets ({@code *} for any)
     */
    public record Stream(
            @DefaultValue("1s") Duration defaultThrottle,
            @DefaultValue("100ms") Duration minThrottle,
            @DefaultValue("60s") Duration maxThrottle,
            @DefaultValue("30m") Duration timeout,
            @DefaultValue("1000") int maxSubscribers,
            @DefaultValue("true") boolean allowUnthrottled,
            @DefaultValue("*") List<String> allowedOrigins) {
    }

    /**
     * Fixed for the life of the process; see {@link com.mds.math.Decimal}. The representable
     * range is {@code ±Long.MAX_VALUE / 10^scale}.
     *
     * @param scale    decimal places kept, 0 to 18
     * @param rounding rounding applied whenever a result has more places than {@code scale}
     */
    public record DecimalSettings(
            @DefaultValue("8") int scale,
            @DefaultValue("HALF_EVEN") RoundingMode rounding) {
    }

    /**
     * @param zone          time zone whose midnight starts each day/week/month/year candle
     * @param flushInterval how often in-progress candles are written and ended periods closed
     */
    public record Ohlc(
            @DefaultValue("UTC") ZoneId zone,
            @DefaultValue("10s") Duration flushInterval) {
    }

    /**
     * SOCKS5 proxy through which every exchange connection is made, both WebSocket streams and the
     * REST calls XChange makes while connecting. Disabled when {@code host} is blank. InfluxDB
     * traffic is not proxied. Proxy authentication is not supported.
     *
     * @param host proxy host name or IP; blank connects to exchanges directly
     * @param port proxy port
     */
    public record SocksProxy(String host, @DefaultValue("1080") int port) {

        public SocksProxy {
            host = host == null ? "" : host.trim();
            if (!host.isEmpty() && (port < 1 || port > 65535)) {
                throw new IllegalArgumentException("mds.socks-proxy.port must be between 1 and 65535, was " + port);
            }
        }

        public boolean enabled() {
            return !host.isEmpty();
        }

        @Override
        public String toString() {
            return enabled() ? host + ":" + port : "none";
        }
    }
}
