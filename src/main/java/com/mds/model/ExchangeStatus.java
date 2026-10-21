package com.mds.model;

import java.time.Instant;
import java.util.List;

/**
 * @param connected  whether the WebSocket is currently open
 * @param pairs      pairs subscribed on this exchange
 * @param lastUpdate when the last ticker or trade arrived; null before the first one
 */
public record ExchangeStatus(String exchange, boolean connected, List<String> pairs, Instant lastUpdate) {
}
