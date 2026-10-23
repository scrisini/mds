package com.mds.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;

import com.hazelcast.map.EntryProcessor;
import com.mds.math.Decimal;
import com.mds.model.Candle;

/**
 * Folds one price into a stored candle, atomically, on the member that holds it (and again on its
 * backup). Starts the series if it doesn't exist. Returns the candles completed on the way, or null
 * when none were.
 */
public record ApplyPrice(Decimal price, Instant at, String zone)
        implements EntryProcessor<CandleKey, Candle, ArrayList<Candle>> {

    @Override
    public ArrayList<Candle> process(Map.Entry<CandleKey, Candle> entry) {
        ZoneId zoneId = ZoneId.of(zone);
        Candle current = entry.getValue();
        if (current == null) {
            CandleKey key = entry.getKey();
            entry.setValue(Candle.open(key.pair().toString(), key.source(), key.period(),
                    key.period().start(at, zoneId), price));
            return null;
        }
        ArrayList<Candle> completed = new ArrayList<>(0);
        entry.setValue(current.withPrice(price, at, zoneId, completed::add));
        return completed.isEmpty() ? null : completed;
    }
}
