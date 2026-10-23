package com.mds.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;

import com.hazelcast.map.EntryProcessor;
import com.mds.model.Candle;

/**
 * Closes a stored candle's ended periods at {@code now} ({@link Candle#rolledForward}), on the member
 * that holds it; removes the series when its source is no longer {@code active}. Returns the candles
 * completed, or null when none were.
 */
public record RollForward(Instant now, boolean active, String zone)
        implements EntryProcessor<CandleKey, Candle, ArrayList<Candle>> {

    @Override
    public ArrayList<Candle> process(Map.Entry<CandleKey, Candle> entry) {
        Candle current = entry.getValue();
        if (current == null) {
            return null;
        }
        ArrayList<Candle> completed = new ArrayList<>(0);
        Candle next = current.rolledForward(now, ZoneId.of(zone), active, completed::add);
        if (next != current) {
            entry.setValue(next); // null removes the series
        }
        return completed.isEmpty() ? null : completed;
    }
}
