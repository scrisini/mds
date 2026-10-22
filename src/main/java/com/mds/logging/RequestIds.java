package com.mds.logging;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * Request ids tie together every log line and error response for one HTTP request. A caller may
 * supply its own (for example from a gateway) in {@link #HEADER}; it's accepted only if it's short
 * and made of safe characters, so it can't forge or break log lines. Otherwise a new one is
 * generated.
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private RequestIds() {
    }

    public static String acceptOrGenerate(String supplied) {
        return supplied != null && SAFE.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
    }

    /** The request id of the current thread's logging context, or null outside a request. */
    public static String current() {
        return MDC.get(LogFields.REQUEST_ID);
    }

    /**
     * Puts {@code requestId} in this thread's logging context until the returned scope is closed:
     * use it for work handed to other threads (executors, async callbacks). Does nothing for null.
     */
    public static Scope bind(String requestId) {
        if (requestId == null) {
            return () -> { };
        }
        MDC.put(LogFields.REQUEST_ID, requestId);
        return () -> MDC.remove(LogFields.REQUEST_ID);
    }

    /** A logging-context binding; closing it removes the binding. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
