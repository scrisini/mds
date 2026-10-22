package com.mds.logging;

import org.slf4j.Logger;
import org.slf4j.event.Level;

/**
 * The catalogue of things mds logs for operators and support. Each event has a stable code
 * ({@code MDS-nnnn}), a fixed level, and a summary that says what happened, what it means for
 * callers and what happens next. Its SOP is the section with the same code in
 * {@code docs/runbook.md}.
 *
 * <p>Codes are a contract with support staff, dashboards and alert rules: never reuse or renumber
 * one. Add a new constant (and its runbook section; {@code LogEventCatalogueTest} checks) instead.
 *
 * <ul>
 *   <li>{@code 1xxx}: lifecycle (startup, configuration, recovery)</li>
 *   <li>{@code 2xxx}: requests and business outcomes (prices, candles)</li>
 *   <li>{@code 3xxx}: dependencies: {@code 30xx} exchanges, {@code 31xx} InfluxDB</li>
 *   <li>{@code 9xxx}: unexpected failures</li>
 * </ul>
 */
public enum LogEvent {

    SERVICE_STARTED("MDS-1001", Level.INFO,
            "Service started and accepting requests"),
    DECIMAL_PRECISION("MDS-1002", Level.INFO,
            "Decimal precision fixed for this run"),
    EXCHANGE_PROXY_ENABLED("MDS-1003", Level.INFO,
            "All exchange connections go through the SOCKS5 proxy"),
    EXCHANGE_PROXY_DISABLED("MDS-1004", Level.INFO,
            "No SOCKS proxy configured; exchanges are connected directly"),
    EXCHANGE_UNSUPPORTED("MDS-1005", Level.WARN,
            "Configured exchange is not supported and is ignored"),
    CANDLES_STARTED("MDS-1006", Level.INFO,
            "Building day, week, month and year candles for the index and each exchange"),
    CANDLES_RECOVERED("MDS-1007", Level.INFO,
            "Reloaded stored candles; ended and missed periods will be completed at the next flush"),

    CANDLES_COMPLETED("MDS-2001", Level.INFO,
            "Candles for ended periods completed and written"),
    QUOTE_VALUE_OUT_OF_RANGE("MDS-2002", Level.WARN,
            "Exchange value exceeds the decimal range and is dropped; logged once per exchange and field"),

    EXCHANGE_CONNECTED("MDS-3001", Level.INFO,
            "Connected to an exchange and streaming its pairs"),
    EXCHANGE_INIT_FAILED("MDS-3002", Level.ERROR,
            "Could not initialise an exchange; it is left out until restart"),
    EXCHANGE_START_FAILED("MDS-3003", Level.ERROR,
            "Could not start streaming from an exchange; it is left out until restart"),
    EXCHANGE_NO_CONFIGURED_PAIRS("MDS-3004", Level.WARN,
            "Exchange lists none of the configured pairs; not connecting to it"),
    EXCHANGE_NO_INSTRUMENT_LIST("MDS-3005", Level.INFO,
            "Exchange publishes no instrument list; subscribing to every configured pair"),
    EXCHANGE_CONNECT_FAILED("MDS-3006", Level.WARN,
            "Connecting to an exchange failed; retrying after the reconnect delay"),
    EXCHANGE_GAVE_UP("MDS-3007", Level.ERROR,
            "Stopped reconnecting to an exchange; no prices from it until restart"),
    EXCHANGE_STREAM_FAILED("MDS-3008", Level.WARN,
            "Exchange channel stream failed; resubscribing after the reconnect delay"),
    EXCHANGE_STREAM_ENDED("MDS-3009", Level.WARN,
            "Exchange channel stream ended; resubscribing after the reconnect delay"),

    INFLUX_REQUEST_FAILED("MDS-3101", Level.ERROR,
            "InfluxDB request for an API call failed; the caller got HTTP 502"),
    ARCHIVE_FAILED("MDS-3102", Level.ERROR,
            "Could not archive prices to InfluxDB; this snapshot is lost, the next is tried at the next interval"),
    CANDLE_WRITE_FAILED("MDS-3103", Level.WARN,
            "Could not write candles to InfluxDB; retrying at the next flush"),
    CANDLE_RELOAD_FAILED("MDS-3104", Level.ERROR,
            "Could not reload candles from InfluxDB at startup; candle series start fresh"),

    UNEXPECTED_ERROR("MDS-9001", Level.ERROR,
            "Unexpected failure while handling a request; the caller got HTTP 500"),
    CANDLE_UPDATES_STOPPED("MDS-9002", Level.ERROR,
            "Candle updates stopped after an unexpected failure; candles stay frozen until restart"),
    UNDELIVERABLE_ERROR("MDS-9003", Level.ERROR,
            "Background failure after its stream was closed; the failed work was dropped");

    private final String code;
    private final Level level;
    private final String summary;

    LogEvent(String code, Level level, String summary) {
        this.code = code;
        this.level = level;
        this.summary = summary;
    }

    public String code() {
        return code;
    }

    public Level level() {
        return level;
    }

    public String summary() {
        return summary;
    }

    /** Link to this event's SOP: the anchor with its code in the runbook. */
    public String reference() {
        return "docs/runbook.md#" + code.toLowerCase();
    }

    /** Starts a log line for this event: {@code LogEvent.X.at(log).with("exchange", id).cause(e).log()}. */
    public EventLog at(Logger logger) {
        return new EventLog(logger, this);
    }
}
