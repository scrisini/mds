package com.mds.logging;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * One log line for a {@link LogEvent}. Writes the event's level and its message as
 * {@code [CODE] summary}, plus structured fields: {@code event.code}, {@code event.reference}
 * (the SOP link) and any {@code mds.*} details added with {@link #with}. In JSON logs every field
 * is searchable; in text logs they're appended as {@code key="value"}.
 *
 * <p>Log identifiers and reasons, never whole requests, personal data or secrets.
 */
public final class EventLog {

    private final LoggingEventBuilder builder;

    EventLog(Logger logger, LogEvent event) {
        this.builder = logger.atLevel(event.level())
                .setMessage("[" + event.code() + "] " + event.summary())
                .addKeyValue(LogFields.EVENT_CODE, event.code())
                .addKeyValue(LogFields.EVENT_REFERENCE, event.reference());
    }

    /**
     * Adds a detail as the field {@code mds.<name>}, e.g. {@code with("exchange", id)}. Numbers and
     * booleans stay typed; anything else is written as its {@code toString()}.
     */
    public EventLog with(String name, Object value) {
        Object field = value == null || value instanceof Number || value instanceof Boolean ? value : value.toString();
        builder.addKeyValue(LogFields.SERVICE_PREFIX + name, field);
        return this;
    }

    /** Attaches the exception; JSON logs record it as {@code error.type}, {@code error.message} and {@code error.stack_trace}. */
    public EventLog cause(Throwable cause) {
        builder.setCause(cause);
        return this;
    }

    public void log() {
        builder.log();
    }
}
