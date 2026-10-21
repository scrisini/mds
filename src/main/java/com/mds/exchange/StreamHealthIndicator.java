package com.mds.exchange;

import java.util.List;

import com.mds.model.ExchangeStatus;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/** Exposed at {@code /actuator/health} as {@code streams}; up while at least one exchange is connected. */
@Component("streams")
public class StreamHealthIndicator extends AbstractHealthIndicator {

    private final MarketStreamManager streams;

    public StreamHealthIndicator(MarketStreamManager streams) {
        this.streams = streams;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        List<ExchangeStatus> status = streams.status();
        if (status.stream().anyMatch(ExchangeStatus::connected)) {
            builder.up();
        } else {
            builder.down();
        }
        status.forEach(s -> builder.withDetail(s.exchange(), s));
    }
}
