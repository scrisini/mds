package com.mds.config;

import com.mds.math.Decimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Fixes {@link Decimal}'s precision from {@code mds.decimal.*}. Runs during context refresh,
 * before {@link com.mds.exchange.MarketStreamManager} starts streams and creates the first values.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class DecimalConfig {

    public DecimalConfig(MdsProperties properties) {
        MdsProperties.DecimalSettings settings = properties.decimal();
        Decimal.configure(settings.scale(), settings.rounding());
        log.info("Decimal precision: scale {}, {}, range ±{}", settings.scale(), settings.rounding(), Decimal.max());
    }
}
