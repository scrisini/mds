package com.mds.web;

import java.util.List;

import com.mds.exchange.MarketStreamManager;
import com.mds.model.ExchangeStatus;
import com.mds.model.MarketPair;
import com.mds.service.PriceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Exchanges and pairs", description = "What the service is connected to and subscribed to")
@RestController
@RequestMapping("/api/v1")
public class MetadataController {

    private final MarketStreamManager streams;
    private final PriceService priceService;

    public MetadataController(MarketStreamManager streams, PriceService priceService) {
        this.streams = streams;
        this.priceService = priceService;
    }

    /** Connection state, subscribed pairs and last update time per exchange. */
    @Operation(summary = "Connection state, subscribed pairs and last update per exchange")
    @GetMapping("/exchanges")
    public List<ExchangeStatus> exchanges() {
        return streams.status();
    }

    /** Pairs subscribed on every exchange that lists them. */
    @Operation(summary = "Pairs subscribed on every exchange that lists them")
    @GetMapping("/pairs")
    public List<String> pairs() {
        return priceService.pairs().stream().map(MarketPair::symbol).toList();
    }
}
