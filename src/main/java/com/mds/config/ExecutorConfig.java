package com.mds.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ExecutorConfig {

    /**
     * Ticker requests are blocking HTTP calls, so each one gets its own virtual thread.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService quoteFetchExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
