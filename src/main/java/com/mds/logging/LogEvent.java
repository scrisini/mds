package com.mds.logging;

import java.util.Locale;

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
 *   <li>{@code 3xxx}: dependencies: {@code 30xx} exchanges, {@code 31xx} InfluxDB, {@code 32xx} the cluster</li>
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
            "Reloaded a pair's stored candles; ended and missed periods will be completed at the next flush"),
    CLUSTER_JOINED("MDS-1008", Level.INFO,
            "Cluster member started; pairs are shared out among the members, each building its own pairs' candles"),
    CLUSTER_SETTINGS_MISMATCH("MDS-1009", Level.ERROR,
            "Decimal precision or candle zone differs from the running cluster; this instance refuses to start"),
    CONFIG_LOADED("MDS-1011", Level.INFO,
            "Configuration loaded from AWS SSM Parameter Store at startup"),
    SECRETS_LOADED("MDS-1012", Level.INFO,
            "Secrets loaded from HashiCorp Vault at startup"),
    CONFIG_LOAD_FAILED("MDS-1013", Level.ERROR,
            "Could not load configuration or secrets at startup; this instance refuses to start"),
    STREAMS_CLOSED_FOR_SHUTDOWN("MDS-1014", Level.INFO,
            "Shutting down: closed every real-time subscription so its client reconnects to another instance"),
    PRODUCTION_SETTINGS_UNSAFE("MDS-1015", Level.ERROR,
            "Production mode with settings that are unsafe in production; this instance refuses to start"),
    CONFIG_CHECK_FAILED("MDS-1016", Level.ERROR,
            "The stored configuration would stop this instance from starting again; fix it before the next restart"),
    CONFIG_CHECK_PASSED("MDS-1017", Level.INFO,
            "The stored configuration is good again; a restart would succeed"),
    CONFIG_CHANGED("MDS-1018", Level.INFO,
            "Parameters or secrets changed since startup; they take effect when the instance restarts"),
    GRPC_SERVER_STARTED("MDS-1019", Level.INFO,
            "gRPC server listening for the MarketData API"),

    CANDLES_COMPLETED("MDS-2001", Level.INFO,
            "Candles for ended periods completed and written"),
    QUOTE_VALUE_OUT_OF_RANGE("MDS-2002", Level.WARN,
            "Exchange value exceeds the decimal range and is dropped; logged once per exchange and field"),
    SUBSCRIBERS_REBALANCED("MDS-2003", Level.INFO,
            "Closed subscriptions above this instance's fair share; their clients reconnect to less loaded instances"),

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

    CLUSTER_MEMBER_JOINED("MDS-3201", Level.INFO,
            "Another instance joined the cluster; some pairs move to it with their candles"),
    CLUSTER_MEMBER_LEFT("MDS-3202", Level.WARN,
            "An instance left the cluster; its pairs move to the remaining instances, which continue their candles"),
    CANDLE_UPDATE_FAILED("MDS-3203", Level.WARN,
            "Could not update the shared candles; prices are left out of candles until the cluster responds"),

    UNEXPECTED_ERROR("MDS-9001", Level.ERROR,
            "Unexpected failure while handling a request; the caller got HTTP 500"),
    CANDLE_UPDATES_STOPPED("MDS-9002", Level.ERROR,
            "Candle updates stopped after an unexpected failure; candles stay frozen until restart"),
    GRPC_UNEXPECTED_ERROR("MDS-9004", Level.ERROR,
            "Unexpected failure while handling a gRPC call; the caller got status INTERNAL"),
    UNDELIVERABLE_ERROR("MDS-9003", Level.ERROR,
            "Background failure after its stream was closed; the failed work was dropped");

    private final String code;
    private final Level level;
    private final String summary;
    // built once: every log line of this event uses them
    private final String message;
    private final String reference;

    LogEvent(String code, Level level, String summary) {
        this.code = code;
        this.level = level;
        this.summary = summary;
        this.message = "[" + code + "] " + summary;
        this.reference = "docs/runbook.md#" + code.toLowerCase(Locale.ROOT);
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

    /** The log message: {@code [CODE] summary}. */
    public String message() {
        return message;
    }

    /** Link to this event's SOP: the anchor with its code in the runbook. */
    public String reference() {
        return reference;
    }

    /** Starts a log line for this event: {@code LogEvent.X.at(log).with("exchange", id).cause(e).log()}. */
    public EventLog at(Logger logger) {
        return new EventLog(logger, this);
    }
}
