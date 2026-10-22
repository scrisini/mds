package com.mds.logging;

/**
 * Log field names. They follow the Elastic Common Schema (ECS) where it has a field, so ELK
 * indexes them without mapping work, and Splunk and New Relic read them as JSON keys. Service
 * details use the {@code mds.*} namespace, as ECS recommends for custom fields.
 */
public final class LogFields {

    /** ECS: the stable code of the event. */
    public static final String EVENT_CODE = "event.code";
    /** ECS: a link to more information about the event; here, its SOP. */
    public static final String EVENT_REFERENCE = "event.reference";
    /** ECS: the id of the HTTP request that caused the log line. */
    public static final String REQUEST_ID = "http.request.id";
    /** Prefix for this service's details. */
    public static final String SERVICE_PREFIX = "mds.";

    private LogFields() {
    }
}
