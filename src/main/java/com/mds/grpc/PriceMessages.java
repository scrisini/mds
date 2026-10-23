package com.mds.grpc;

import com.mds.grpc.v1.Failure;
import com.mds.grpc.v1.Price;
import com.mds.grpc.v1.Quote;
import com.mds.math.Decimal;
import com.mds.model.AggregatedPrice;
import com.mds.model.PriceQuote;
import com.mds.model.QuoteFailure;

/**
 * The model as gRPC messages. Numbers travel as {@link Decimal}'s own units, with the scale once per
 * price: no conversion, and a varint of a few bytes instead of a decimal string. An absent number is
 * left unset rather than sent as zero.
 */
final class PriceMessages {

    private PriceMessages() {
    }

    static Price price(AggregatedPrice price, boolean includeQuotes) {
        Price.Builder message = Price.newBuilder()
                .setPair(price.pair())
                .setTimestampMs(price.timestamp().toEpochMilli())
                .setScale(Decimal.scale())
                .setSources(price.sources());
        if (price.mean() != null) {
            message.setMean(price.mean().units());
        }
        if (price.median() != null) {
            message.setMedian(price.median().units());
        }
        if (price.vwap() != null) {
            message.setVwap(price.vwap().units());
        }
        if (price.min() != null) {
            message.setMin(price.min().units());
        }
        if (price.max() != null) {
            message.setMax(price.max().units());
        }
        if (price.bestBid() != null) {
            message.setBestBid(price.bestBid().units());
        }
        if (price.bestBidExchange() != null) {
            message.setBestBidExchange(price.bestBidExchange());
        }
        if (price.bestAsk() != null) {
            message.setBestAsk(price.bestAsk().units());
        }
        if (price.bestAskExchange() != null) {
            message.setBestAskExchange(price.bestAskExchange());
        }
        if (includeQuotes) {
            for (PriceQuote quote : price.quotes()) {
                message.addQuotes(quote(quote));
            }
            for (QuoteFailure failure : price.failures()) {
                message.addFailures(Failure.newBuilder().setExchange(failure.exchange()).setReason(failure.reason()));
            }
        }
        return message.build();
    }

    private static Quote quote(PriceQuote quote) {
        Quote.Builder message = Quote.newBuilder()
                .setExchange(quote.exchange())
                .setTimestampMs(quote.timestamp().toEpochMilli());
        if (quote.last() != null) {
            message.setLast(quote.last().units());
        }
        if (quote.bid() != null) {
            message.setBid(quote.bid().units());
        }
        if (quote.ask() != null) {
            message.setAsk(quote.ask().units());
        }
        if (quote.high() != null) {
            message.setHigh(quote.high().units());
        }
        if (quote.low() != null) {
            message.setLow(quote.low().units());
        }
        if (quote.volume() != null) {
            message.setVolume(quote.volume().units());
        }
        if (quote.quoteVolume() != null) {
            message.setQuoteVolume(quote.quoteVolume().units());
        }
        return message.build();
    }
}
