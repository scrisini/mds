package com.mds.model;

import java.time.Instant;
import java.util.List;

/**
 * @param state      overall health of the exchange
 * @param connected  whether the WebSocket is currently open
 * @param pairs      pairs subscribed on this exchange; empty when it failed before subscribing
 * @param lastUpdate when the last ticker or trade arrived; null before the first one
 * @param reason     why the exchange is {@link ExchangeState#FAILED}; null otherwise
 */
public record ExchangeStatus(String exchange, ExchangeState state, boolean connected, List<String> pairs,
                             Instant lastUpdate, String reason) {
}
