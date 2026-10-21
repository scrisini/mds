package com.mds.service;

import java.util.List;

import com.mds.model.MarketPair;

public class PairNotSubscribedException extends RuntimeException {

    public PairNotSubscribedException(MarketPair pair, List<MarketPair> subscribed) {
        super("Pair %s is not subscribed; available: %s".formatted(pair, subscribed));
    }
}
