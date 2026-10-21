package com.mds.model;

/** Published by the quote book whenever an exchange's quote for a pair changes. */
public record QuoteUpdate(MarketPair pair, PriceQuote quote) {
}
