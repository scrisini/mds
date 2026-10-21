package com.mds.exchange;

import java.util.Arrays;
import java.util.Optional;

import info.bitrich.xchangestream.binance.BinanceStreamingExchange;
import info.bitrich.xchangestream.binance.BinanceUsStreamingExchange;
import info.bitrich.xchangestream.bitfinex.BitfinexStreamingExchange;
import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.kraken.KrakenStreamingExchange;
import info.bitrich.xchangestream.kucoin.KucoinStreamingExchange;

/**
 * Exchanges whose XChange streaming modules are on the classpath. To add one, declare the
 * {@code xchange-stream-<name>} dependency in the build and add an entry here.
 */
public enum SupportedExchange {
    BINANCE("binance", BinanceStreamingExchange.class, false),
    BINANCE_US("binanceus", BinanceUsStreamingExchange.class, false),
    KRAKEN("kraken", KrakenStreamingExchange.class, false),
    // Bitstamp has no ticker channel; XChange derives its ticker from the order book, which
    // carries bid/ask only, so the last price comes from the trade stream.
    BITSTAMP("bitstamp", PatchedBitstampStreamingExchange.class, true),
    KUCOIN("kucoin", KucoinStreamingExchange.class, false),
    BITFINEX("bitfinex", BitfinexStreamingExchange.class, false);

    private final String id;
    private final Class<? extends StreamingExchange> exchangeClass;
    private final boolean lastPriceFromTrades;

    SupportedExchange(String id, Class<? extends StreamingExchange> exchangeClass, boolean lastPriceFromTrades) {
        this.id = id;
        this.exchangeClass = exchangeClass;
        this.lastPriceFromTrades = lastPriceFromTrades;
    }

    public String id() {
        return id;
    }

    public Class<? extends StreamingExchange> exchangeClass() {
        return exchangeClass;
    }

    /** Whether the trade stream must also be subscribed to obtain a last price. */
    public boolean lastPriceFromTrades() {
        return lastPriceFromTrades;
    }

    public static Optional<SupportedExchange> fromId(String id) {
        return Arrays.stream(values()).filter(e -> e.id.equalsIgnoreCase(id)).findFirst();
    }
}
