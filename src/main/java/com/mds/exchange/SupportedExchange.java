package com.mds.exchange;

import java.util.Arrays;
import java.util.Optional;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.binance.BinanceExchange;
import org.knowm.xchange.binance.BinanceUsExchange;
import org.knowm.xchange.bitfinex.BitfinexExchange;
import org.knowm.xchange.bitstamp.BitstampExchange;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kucoin.KucoinExchange;

/**
 * Exchanges whose XChange modules are on the classpath. To add one, declare the
 * {@code xchange-<name>} dependency in the build and add an entry here.
 */
public enum SupportedExchange {
    BINANCE("binance", BinanceExchange.class),
    BINANCE_US("binanceus", BinanceUsExchange.class),
    KRAKEN("kraken", KrakenExchange.class),
    BITSTAMP("bitstamp", BitstampExchange.class),
    KUCOIN("kucoin", KucoinExchange.class),
    BITFINEX("bitfinex", BitfinexExchange.class);

    private final String id;
    private final Class<? extends Exchange> exchangeClass;

    SupportedExchange(String id, Class<? extends Exchange> exchangeClass) {
        this.id = id;
        this.exchangeClass = exchangeClass;
    }

    public String id() {
        return id;
    }

    public Class<? extends Exchange> exchangeClass() {
        return exchangeClass;
    }

    public static Optional<SupportedExchange> fromId(String id) {
        return Arrays.stream(values()).filter(e -> e.id.equalsIgnoreCase(id)).findFirst();
    }
}
