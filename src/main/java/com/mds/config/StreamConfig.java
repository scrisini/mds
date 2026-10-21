package com.mds.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StreamConfig {

    /**
     * Writes to SSE clients are blocking socket I/O; virtual threads keep a slow client from
     * holding a platform thread.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService streamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
