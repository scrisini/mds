package com.mds.web;

import java.util.List;

import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.model.PriceQuote;
import com.mds.persistence.PriceRepository;
import com.mds.service.PriceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live prices, computed on each request from the latest WebSocket updates. */
@Tag(name = "Prices", description = "Live prices, computed on each request from the latest exchange updates")
@RestController
@RequestMapping("/api/v1/prices")
public class PriceController {

    private final PriceService priceService;
    private final PriceRepository repository;

    public PriceController(PriceService priceService, PriceRepository repository) {
        this.priceService = priceService;
        this.repository = repository;
    }

    /** Aggregate for every subscribed pair that has a fresh quote. */
    @Operation(summary = "Aggregate for every subscribed pair with a fresh quote")
    @GetMapping
    public List<AggregatedPrice> all() {
        return priceService.aggregates();
    }

    /** Aggregate for one pair, e.g. {@code /api/v1/prices/BTC-USDT}. */
    @Operation(summary = "Aggregate for one pair", description = "503 until a fresh quote has arrived for it.")
    @GetMapping("/{pair}")
    public AggregatedPrice aggregate(@Parameter(example = "BTC-USDT") @PathVariable String pair) {
        return priceService.aggregate(MarketPair.parse(pair));
    }

    /** Latest streamed quote from one exchange, e.g. {@code /api/v1/prices/BTC-USDT/kraken}. */
    @Operation(summary = "Latest quote from one exchange")
    @GetMapping("/{pair}/{exchange}")
    public PriceQuote quote(@Parameter(example = "BTC-USDT") @PathVariable String pair,
                            @Parameter(description = "Exchange id", example = "kraken") @PathVariable String exchange) {
        return priceService.quote(exchange, MarketPair.parse(pair));
    }

    /** Archives a snapshot of the current aggregates immediately. */
    @Operation(summary = "Archive a snapshot of the current aggregates now",
            description = "Snapshots are also archived every mds.archive.interval.")
    @PostMapping("/archive")
    public List<AggregatedPrice> archive() {
        List<AggregatedPrice> aggregates = priceService.aggregates();
        repository.save(aggregates);
        return aggregates;
    }
}
