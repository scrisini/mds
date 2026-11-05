package com.mds.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.mds.exchange.MarketStreamManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Beans close in the reverse of the order they were created. The scheduler has to close first: that
 * is where running jobs are waited for, and they use InfluxDB and the cluster. Nothing else enforces
 * this, so a component that injects the {@code TaskScheduler} early would break it unnoticed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mds.pairs=BTC/USDT",
        "mds.influx.url=http://127.0.0.1:1"})
class ShutdownOrderTest {

    @MockitoBean
    private MarketStreamManager streams;

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void theSchedulerClosesBeforeWhatItsJobsUse() {
        List<String> created = List.of(((DefaultListableBeanFactory) context.getBeanFactory()).getSingletonNames());

        assertThat(created.indexOf("taskScheduler"))
                .isGreaterThan(created.indexOf("influxDBClient"))
                .isGreaterThan(created.indexOf("hazelcastInstance"));
        assertThat(created).contains("influxDBClient", "hazelcastInstance");
    }
}
