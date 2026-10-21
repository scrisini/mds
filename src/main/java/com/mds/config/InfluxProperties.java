package com.mds.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection settings for the InfluxDB 2.x instance used as the price archive.
 */
@ConfigurationProperties("mds.influx")
public record InfluxProperties(
        @DefaultValue("http://localhost:8086") String url,
        String token,
        @DefaultValue("mds") String org,
        @DefaultValue("prices") String bucket) {
}
