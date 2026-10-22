package com.mds.config;

import com.mds.logging.LogEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Logs lifecycle events from the catalogue, so support can see when an instance became ready. */
@Slf4j
@Component
class LifecycleLogging {

    @EventListener
    void started(ApplicationReadyEvent event) {
        Integer port = event.getApplicationContext().getEnvironment().getProperty("local.server.port", Integer.class);
        LogEvent.SERVICE_STARTED.at(log).with("port", port).log();
    }
}
