package com.mds.config;

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
 */
@ConfigurationProperties("mds")
public record MdsProperties(
        @DefaultValue({"binance", "kraken", "bitstamp", "kucoin", "bitfinex"}) List<String> exchanges,
        @DefaultValue({"BTC/USDT", "ETH/USDT"}) List<String> pairs,
        @DefaultValue("10s") Duration connectionTimeout,
        @DefaultValue("5s") Duration reconnectDelay,
        @DefaultValue("60s") Duration staleAfter,
        @DefaultValue Archive archive) {

    /**
     * @param enabled  whether snapshots of the live quotes are written to InfluxDB
     * @param interval time between snapshots
     */
    public record Archive(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") Duration interval) {
    }
}
