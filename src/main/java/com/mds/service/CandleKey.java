package com.mds.service;

import com.hazelcast.partition.PartitionAware;
import com.mds.model.CandlePeriod;
import com.mds.model.MarketPair;

/**
 * Identifies one candle series in the cluster. Partitioned by pair, so all of a pair's series live
 * on the member that owns the pair ({@link com.mds.cluster.Cluster#owns}).
 */
public record CandleKey(String source, MarketPair pair, CandlePeriod period) implements PartitionAware<String> {

    @Override
    public String getPartitionKey() {
        return pair.toString();
    }
}
