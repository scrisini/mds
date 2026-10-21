package com.mds.web;

import java.util.List;

import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.model.PriceQuote;
import com.mds.persistence.PriceRepository;
import com.mds.service.PriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/prices")
public class PriceController {

    private final PriceService priceService;
    private final PriceRepository repository;

    public PriceController(PriceService priceService, PriceRepository repository) {
        this.priceService = priceService;
        this.repository = repository;
    }

    /** Latest cached aggregate for every configured pair. */
    @GetMapping
    public List<AggregatedPrice> all() {
        return priceService.latestForConfiguredPairs();
    }

    /**
     * Aggregate for one pair, e.g. {@code /api/v1/prices/BTC-USDT}. Served from cache
     * unless {@code live=true}; pairs not in the configured set are always fetched live.
     */
    @GetMapping("/{pair}")
    public AggregatedPrice aggregate(@PathVariable String pair,
                                     @RequestParam(defaultValue = "false") boolean live) {
        MarketPair marketPair = MarketPair.parse(pair);
        return live ? priceService.refresh(marketPair) : priceService.latest(marketPair);
    }

    /** Live ticker from a single exchange, e.g. {@code /api/v1/prices/BTC-USDT/kraken}. */
    @GetMapping("/{pair}/{exchange}")
    public PriceQuote quote(@PathVariable String pair, @PathVariable String exchange) {
        return priceService.quote(exchange, MarketPair.parse(pair));
    }

    /** Runs a collection cycle immediately and archives it. */
    @PostMapping("/collect")
    public List<AggregatedPrice> collect() {
        List<AggregatedPrice> aggregates = priceService.refreshConfiguredPairs();
        repository.save(aggregates);
        return aggregates;
    }
}
