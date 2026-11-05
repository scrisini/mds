package com.mds.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StreamConfig {

    /**
     * Writes to SSE clients are blocking socket I/O; virtual threads keep a slow client from
     * holding a platform thread. Closed only after every stream and the web and gRPC servers have
     * stopped, so anything still running is stuck: interrupt it, as {@code close()} would wait for it
     * without limit and hold up the rest of the shutdown.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService streamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
