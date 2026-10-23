package com.mds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

import com.mds.model.QuoteUpdate;
import com.mds.math.Decimal;
import com.mds.model.MarketPair;
import com.mds.model.PriceQuote;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.knowm.xchange.dto.marketdata.Ticker;

class QuoteBookTest {

    private static final MarketPair BTC_USD = MarketPair.parse("BTC-USD");
    private static final Instant T0 = Instant.parse("2026-10-21T12:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);

    private final Clock clock = mock(Clock.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QuoteBook book = new QuoteBook(clock, registry);

    @Test
    void countsUpdatesPerExchangeAndStampsWhenTheyArrived() {
        when(clock.instant()).thenReturn(T0);
        List<QuoteUpdate> updates = new ArrayList<>();
        book.updates().subscribe(updates::add);
        long before = System.nanoTime();

        book.onTicker("kraken", BTC_USD, ticker("100", "99", "101", "5"));
        book.onTrade("kraken", BTC_USD, new BigDecimal("100.5"));
        book.onTrade("bitstamp", BTC_USD, new BigDecimal("101"));

        assertThat(registry.get("mds.quotes.received").tag("exchange", "kraken").counter().count()).isEqualTo(2);
        assertThat(registry.get("mds.quotes.received").tag("exchange", "bitstamp").counter().count()).isEqualTo(1);
        assertThat(updates).allSatisfy(update -> assertThat(update.receivedNanos()).isBetween(before, System.nanoTime()));
    }

    @Test
    void partialTickerKeepsPreviouslyKnownFields() {
        when(clock.instant()).thenReturn(T0, T1);

        book.onTicker("kraken", BTC_USD, ticker("100", "99", "101", "5"));
        book.onTicker("kraken", BTC_USD, ticker(null, "99.5", "100.5", null));

        PriceQuote quote = book.latest("kraken", BTC_USD).orElseThrow();
        assertThat(quote.last()).isEqualTo(Decimal.parse("100"));
        assertThat(quote.bid()).isEqualTo(Decimal.parse("99.5"));
        assertThat(quote.ask()).isEqualTo(Decimal.parse("100.5"));
        assertThat(quote.volume()).isEqualTo(Decimal.parse("5"));
        assertThat(quote.timestamp()).isEqualTo(T1);
    }

    @Test
    void tradesSupplyLastPriceForOrderBookDerivedTickers() {
        when(clock.instant()).thenReturn(T0, T1);

        book.onTicker("bitstamp", BTC_USD, ticker(null, "99", "101", null));
        book.onTrade("bitstamp", BTC_USD, new BigDecimal("100.25"));

        PriceQuote quote = book.latest("bitstamp", BTC_USD).orElseThrow();
        assertThat(quote.last()).isEqualTo(Decimal.parse("100.25"));
        assertThat(quote.bid()).isEqualTo(Decimal.parse("99"));
        assertThat(book.lastUpdate("bitstamp")).contains(T1);
        assertThat(book.latest("kraken", BTC_USD)).isEmpty();
    }

    private static Ticker ticker(String last, String bid, String ask, String volume) {
        return new Ticker.Builder()
                .instrument(BTC_USD.toCurrencyPair())
                .last(dec(last)).bid(dec(bid)).ask(dec(ask)).volume(dec(volume))
                .build();
    }

    private static BigDecimal dec(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
