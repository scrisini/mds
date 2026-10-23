package com.mds.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.mds.math.Decimal;
import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.model.PriceQuote;
import com.mds.model.QuoteFailure;
import org.junit.jupiter.api.Test;

class PriceAggregatorTest {

    private static final MarketPair BTC_USDT = MarketPair.parse("BTC-USDT");
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private final PriceAggregator aggregator = new PriceAggregator();

    @Test
    void computesCrossExchangeStatistics() {
        List<PriceQuote> quotes = List.of(
                quote("binance", "100", "99", "101", "3"),
                quote("kraken", "102", "101.5", "103", "1"),
                quote("bitstamp", "104", "103", "100.5", null));

        AggregatedPrice result = aggregator.aggregate(BTC_USDT, quotes, List.of(), NOW).orElseThrow();

        assertThat(result.sources()).isEqualTo(3);
        assertThat(result.mean()).isEqualTo(Decimal.parse("102"));
        assertThat(result.median()).isEqualTo(Decimal.parse("102"));
        // (100*3 + 102*1) / 4; bitstamp has no volume so is excluded
        assertThat(result.vwap()).isEqualTo(Decimal.parse("100.5"));
        assertThat(result.min()).isEqualTo(Decimal.parse("100"));
        assertThat(result.max()).isEqualTo(Decimal.parse("104"));
        assertThat(result.bestBid()).isEqualTo(Decimal.parse("103"));
        assertThat(result.bestBidExchange()).isEqualTo("bitstamp");
        assertThat(result.bestAsk()).isEqualTo(Decimal.parse("100.5"));
        assertThat(result.bestAskExchange()).isEqualTo("bitstamp");
    }

    @Test
    void medianOfEvenCountAveragesMiddleValues() {
        List<PriceQuote> quotes = List.of(
                quote("a", "10", null, null, null),
                quote("b", "40", null, null, null),
                quote("c", "20", null, null, null),
                quote("d", "30", null, null, null));

        AggregatedPrice result = aggregator.aggregate(BTC_USDT, quotes, List.of(), NOW).orElseThrow();

        assertThat(result.median()).isEqualTo(Decimal.parse("25"));
        assertThat(result.vwap()).isNull();
        assertThat(result.bestBid()).isNull();
    }

    @Test
    void isEmptyWhenNoQuoteHasALastPrice() {
        List<QuoteFailure> failures = List.of(new QuoteFailure("kraken", "timed out"));

        assertThat(aggregator.aggregate(BTC_USDT, List.of(quote("binance", null, "99", "101", "3")), failures, NOW))
                .isEmpty();
    }

    @Test
    void bestBidAndAskSkipMissingAndNonPositiveValuesAndKeepTheFirstOnATie() {
        List<PriceQuote> quotes = List.of(
                quote("a", "100", null, "0", null),
                quote("b", "100", "99", "101", null),
                quote("c", "100", "99", "101", null),
                quote("d", "100", "-1", null, null));

        AggregatedPrice result = aggregator.aggregate(BTC_USDT, quotes, List.of(), NOW).orElseThrow();

        assertThat(result.bestBidExchange()).isEqualTo("b");
        assertThat(result.bestAskExchange()).isEqualTo("b");
    }

    private static PriceQuote quote(String exchange, String last, String bid, String ask, String volume) {
        return new PriceQuote(exchange, BTC_USDT.toString(), dec(last), dec(bid), dec(ask),
                null, null, dec(volume), null, NOW);
    }

    private static Decimal dec(String value) {
        return value == null ? null : Decimal.parse(value);
    }
}
