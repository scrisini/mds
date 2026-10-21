package com.mds.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
public class InfluxConfig {

    @Bean(destroyMethod = "close")
    public InfluxDBClient influxDBClient(InfluxProperties properties) {
        Assert.hasText(properties.token(), "mds.influx.token must be set (env MDS_INFLUX_TOKEN)");
        return InfluxDBClientFactory.create(
                properties.url(), properties.token().toCharArray(), properties.org(), properties.bucket());
    }
}
