package com.mds.service;

import java.util.List;

import com.mds.logging.LogEvent;
import com.mds.model.AggregatedPrice;
import com.mds.persistence.PriceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically snapshots the live aggregates, and the quotes behind them, into InfluxDB.
 * Quotes are written at their own receive time, so an exchange that hasn't updated since the
 * previous snapshot rewrites the same point rather than adding a duplicate.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mds.archive.enabled", havingValue = "true", matchIfMissing = true)
public class PriceArchiver {

    private final PriceService priceService;
    private final PriceRepository repository;

    public PriceArchiver(PriceService priceService, PriceRepository repository) {
        this.priceService = priceService;
        this.repository = repository;
    }

    @Scheduled(initialDelayString = "${mds.archive.interval:5s}", fixedRateString = "${mds.archive.interval:5s}")
    public void archive() {
        List<AggregatedPrice> aggregates = priceService.aggregates();
        try {
            repository.save(aggregates);
            log.debug("Archived {} aggregates", aggregates.size());
        } catch (RuntimeException e) {
            LogEvent.ARCHIVE_FAILED.at(log).with("count", aggregates.size()).with("reason", e.getMessage()).log();
        }
    }
}
