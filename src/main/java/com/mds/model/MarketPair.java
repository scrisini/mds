package com.mds.model;

import java.util.Locale;
import java.util.regex.Pattern;

import org.knowm.xchange.currency.CurrencyPair;

/**
 * A base/counter currency pair such as BTC/USDT. Accepts {@code BTC/USDT}, {@code BTC-USDT}
 * or {@code BTC_USDT}; codes are restricted to alphanumerics so a pair is always safe to
 * embed in a Flux query.
 */
public record MarketPair(String base, String counter) {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{2,15}");
    private static final Pattern SEPARATOR = Pattern.compile("[/\\-_]");

    public MarketPair {
        base = normalise(base);
        counter = normalise(counter);
    }

    public static MarketPair parse(String value) {
        String[] parts = SEPARATOR.split(value == null ? "" : value.trim());
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid pair '%s', expected BASE-COUNTER (e.g. BTC-USDT)".formatted(value));
        }
        return new MarketPair(parts[0], parts[1]);
    }

    public CurrencyPair toCurrencyPair() {
        return new CurrencyPair(base, counter);
    }

    /** URL-friendly form, e.g. {@code BTC-USDT}. */
    public String symbol() {
        return base + "-" + counter;
    }

    @Override
    public String toString() {
        return base + "/" + counter;
    }

    private static String normalise(String code) {
        String upper = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(upper).matches()) {
            throw new IllegalArgumentException("Invalid currency code '%s'".formatted(code));
        }
        return upper;
    }
}
