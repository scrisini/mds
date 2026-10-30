package com.mds.model;

/**
 * Health of one configured exchange, worst first. {@link #level()} is the value of the
 * {@code mds.exchange.state} gauge, so dashboards can map it back to a name.
 */
public enum ExchangeState {

    /** Not streaming and not retrying: unsupported, failed to start, or gave up; see the reason. */
    FAILED(0),
    /** The WebSocket is closed; reconnecting. */
    DISCONNECTED(1),
    /** The WebSocket is open, but nothing arrived within {@code mds.stale-after}. */
    STALE(2),
    /** The WebSocket is open and updates are arriving. */
    CONNECTED(3);

    private final int level;

    ExchangeState(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }
}
