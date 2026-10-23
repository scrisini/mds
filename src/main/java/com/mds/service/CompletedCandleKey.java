package com.mds.service;

import java.time.Instant;

import com.hazelcast.partition.PartitionAware;

/** A completed candle waiting to be written: its series and period start. Lives with the series. */
public record CompletedCandleKey(CandleKey series, Instant start) implements PartitionAware<String> {

    @Override
    public String getPartitionKey() {
        return series.getPartitionKey();
    }
}
