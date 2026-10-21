package com.mds.persistence;

import com.influxdb.client.InfluxDBClient;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/** Exposed at {@code /actuator/health} as {@code influx}. */
@Component("influx")
public class InfluxHealthIndicator extends AbstractHealthIndicator {

    private final InfluxDBClient client;

    public InfluxHealthIndicator(InfluxDBClient client) {
        this.client = client;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        if (Boolean.TRUE.equals(client.ping())) {
            builder.up().withDetail("version", client.version());
        } else {
            builder.down();
        }
    }
}
