package com.mds.web;

import java.util.List;
import java.util.Set;

import com.mds.exchange.ExchangeRegistry;
import com.mds.model.MarketPair;
import com.mds.service.PriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MetadataController {

    private final ExchangeRegistry registry;
    private final PriceService priceService;

    public MetadataController(ExchangeRegistry registry, PriceService priceService) {
        this.registry = registry;
        this.priceService = priceService;
    }

    /** Exchanges that initialised successfully. */
    @GetMapping("/exchanges")
    public Set<String> exchanges() {
        return registry.ids();
    }

    /** Pairs collected and archived on every cycle. */
    @GetMapping("/pairs")
    public List<String> pairs() {
        return priceService.configuredPairs().stream().map(MarketPair::symbol).toList();
    }
}
