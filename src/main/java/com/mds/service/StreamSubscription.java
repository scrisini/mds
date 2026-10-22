package com.mds.service;

import com.mds.model.MarketPair;

/** A live price subscription; closing it stops updates and frees its slot. Idempotent. */
public interface StreamSubscription extends AutoCloseable {

    MarketPair pair();

    StreamRate rate();

    @Override
    void close();
}
