package com.mds.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Aggregator settings bound from the {@code mds.*} namespace.
 *
 * @param exchanges    exchange identifiers to connect to (see {@link com.mds.exchange.SupportedExchange})
 * @param pairs        currency pairs collected on every cycle, e.g. {@code BTC/USDT}
 * @param fetchTimeout upper bound for a single ticker request against one exchange
 * @param collector    scheduled collection settings
 */
@ConfigurationProperties("mds")
public record MdsProperties(
        @DefaultValue({"binance", "kraken", "bitstamp", "kucoin", "bitfinex"}) List<String> exchanges,
        @DefaultValue({"BTC/USDT", "ETH/USDT"}) List<String> pairs,
        @DefaultValue("5s") Duration fetchTimeout,
        @DefaultValue Collector collector) {

    /**
     * @param enabled  whether prices are polled and persisted on a schedule
     * @param interval delay between the end of one collection cycle and the start of the next
     */
    public record Collector(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30s") Duration interval) {
    }
}
