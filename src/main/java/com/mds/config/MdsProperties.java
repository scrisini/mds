package com.mds.config;

import java.math.RoundingMode;
import java.time.Duration;
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
        @DefaultValue DecimalSettings decimal) {

    /**
     * @param enabled  whether snapshots of the live quotes are written to InfluxDB
     * @param interval time between snapshots
     */
    public record Archive(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") Duration interval) {
    }

    /**
     * @param defaultThrottle minimum time between events when the client doesn't ask for one
     * @param minThrottle     lowest throttle a client may request
     * @param maxThrottle     highest throttle a client may request
     * @param timeout         connection lifetime, after which clients reconnect
     * @param maxSubscribers  concurrent subscriptions allowed across all clients
     */
    public record Stream(
            @DefaultValue("1s") Duration defaultThrottle,
            @DefaultValue("100ms") Duration minThrottle,
            @DefaultValue("60s") Duration maxThrottle,
            @DefaultValue("30m") Duration timeout,
            @DefaultValue("1000") int maxSubscribers) {
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
}
