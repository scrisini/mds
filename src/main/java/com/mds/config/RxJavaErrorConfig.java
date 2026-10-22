package com.mds.config;

import java.io.IOException;

import com.mds.logging.LogEvent;
import io.reactivex.rxjava3.exceptions.UndeliverableException;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Routes errors RxJava can't deliver (raised after their stream was disposed) through the log
 * instead of RxJava's default, which prints a stack trace to stderr and so breaks JSON-only logs.
 * The usual case is a real-time client that disconnected while an update was being sent: that's
 * expected and logged at DEBUG. Anything else is {@link LogEvent#UNDELIVERABLE_ERROR}.
 *
 * <p>Installed during context refresh, before {@link com.mds.exchange.MarketStreamManager} opens
 * any stream.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RxJavaErrorConfig {

    public RxJavaErrorConfig() {
        RxJavaPlugins.setErrorHandler(RxJavaErrorConfig::undeliverable);
    }

    static void undeliverable(Throwable error) {
        Throwable cause = error instanceof UndeliverableException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof IOException || cause instanceof InterruptedException) {
            log.debug("Ignoring {} raised after its stream was closed: {}", cause.getClass().getSimpleName(),
                    cause.getMessage());
            return;
        }
        LogEvent.UNDELIVERABLE_ERROR.at(log).with("type", cause.getClass().getName()).cause(cause).log();
    }
}
