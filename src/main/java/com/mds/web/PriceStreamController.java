package com.mds.web;

import java.util.Optional;

import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.service.PriceStreamService;
import com.mds.service.StreamRate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Server-Sent Event subscriptions to live prices. */
@Tag(name = "Streams", description = "Live prices as Server-Sent Events; WebSocket and gRPC streams are in the README")
@RestController
@RequestMapping("/api/v1/stream")
public class PriceStreamController {

    private final PriceStreamService streamService;

    public PriceStreamController(PriceStreamService streamService) {
        this.streamService = streamService;
    }

    /**
     * Live aggregate for one pair, e.g. {@code /api/v1/stream/prices/BTC-USDT?rate=2}.
     * {@code rate} is updates per second ({@code 1}, {@code 2}, {@code 3}…) or {@code unlimited};
     * alternatively {@code throttle} gives a minimum interval ({@code 250ms}, {@code 2s}, {@code PT1S}).
     */
    @Operation(summary = "Live aggregate for one pair as Server-Sent Events",
            description = "Swagger UI waits for the stream to end; use curl -N or an EventSource to watch it.")
    @ApiResponse(responseCode = "200", description = "One event per update, each an aggregate",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = @Schema(implementation = AggregatedPrice.class)))
    @GetMapping(path = "/prices/{pair}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter prices(@Parameter(example = "BTC-USDT") @PathVariable String pair,
                             @Parameter(description = "Updates per second (1, 2, 3...) or unlimited")
                             @RequestParam Optional<String> rate,
                             @Parameter(description = "Or a minimum interval: 250ms, 2s, PT1S")
                             @RequestParam Optional<String> throttle) {
        StreamRate streamRate = streamService.resolveRate(rate, throttle.map(DurationStyle::detectAndParse));
        return streamService.subscribe(MarketPair.parse(pair), streamRate);
    }
}
