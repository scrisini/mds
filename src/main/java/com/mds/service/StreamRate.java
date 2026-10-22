package com.mds.service;

import java.time.Duration;

/**
 * How often a streaming subscriber may receive updates: at most one per {@code minInterval},
 * always the latest state, or every update when {@code minInterval} is null (unthrottled).
 *
 * @param perSecond the requested updates per second when created from a rate, otherwise 0; kept
 *                  because intervals like 1s/3 aren't exact, so the rate can't be derived back
 */
public record StreamRate(Duration minInterval, int perSecond) {

    public static final String UNLIMITED = "unlimited";

    public static StreamRate unthrottled() {
        return new StreamRate(null, 0);
    }

    public static StreamRate every(Duration interval) {
        return new StreamRate(interval, 0);
    }

    /** At most {@code updates} per second. */
    public static StreamRate perSecond(int updates) {
        return new StreamRate(Duration.ofSeconds(1).dividedBy(updates), updates);
    }

    public boolean throttled() {
        return minInterval != null;
    }

    /** {@code unlimited}, {@code 3/s} (also for a 500ms throttle: {@code 2/s}), or {@code every PT1.5S}. */
    @Override
    public String toString() {
        if (!throttled()) {
            return UNLIMITED;
        }
        if (perSecond > 0) {
            return perSecond + "/s";
        }
        long second = Duration.ofSeconds(1).toNanos();
        long nanos = minInterval.toNanos();
        if (nanos <= second && second % nanos == 0) {
            return (second / nanos) + "/s"; // e.g. a 500ms throttle
        }
        return "every " + minInterval;
    }
}
