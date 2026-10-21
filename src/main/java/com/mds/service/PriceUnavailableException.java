package com.mds.service;

import java.util.List;

import com.mds.model.MarketPair;
import com.mds.model.QuoteFailure;

/** No exchange returned a usable price for the requested pair. */
public class PriceUnavailableException extends RuntimeException {

    private final transient List<QuoteFailure> failures;

    public PriceUnavailableException(MarketPair pair, List<QuoteFailure> failures) {
        super("No exchange returned a price for " + pair);
        this.failures = List.copyOf(failures);
    }

    public List<QuoteFailure> failures() {
        return failures;
    }
}
