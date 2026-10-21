package com.mds.service;

import java.util.List;

import com.mds.model.AggregatedPrice;
import com.mds.persistence.PriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically refreshes all configured pairs and archives the result. */
@Component
@ConditionalOnProperty(name = "mds.collector.enabled", havingValue = "true", matchIfMissing = true)
public class PriceCollector {

    private static final Logger log = LoggerFactory.getLogger(PriceCollector.class);

    private final PriceService priceService;
    private final PriceRepository repository;

    public PriceCollector(PriceService priceService, PriceRepository repository) {
        this.priceService = priceService;
        this.repository = repository;
    }

    @Scheduled(initialDelayString = "${mds.collector.initial-delay:5s}",
            fixedDelayString = "${mds.collector.interval:30s}")
    public void collect() {
        List<AggregatedPrice> aggregates = priceService.refreshConfiguredPairs();
        try {
            repository.save(aggregates);
            log.debug("Archived {} aggregates", aggregates.size());
        } catch (RuntimeException e) {
            log.error("Failed to archive prices to InfluxDB: {}", e.getMessage());
        }
    }
}
