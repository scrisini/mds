package com.mds.web;

import java.util.Optional;

import com.mds.model.MarketPair;
import com.mds.service.PriceStreamService;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Server-Sent Event subscriptions to live prices. */
@RestController
@RequestMapping("/api/v1/stream")
public class PriceStreamController {

    private final PriceStreamService streamService;

    public PriceStreamController(PriceStreamService streamService) {
        this.streamService = streamService;
    }

    /**
     * Live aggregate for one pair, e.g. {@code /api/v1/stream/prices/BTC-USDT?throttle=500ms}.
     * {@code throttle} accepts {@code 250ms}, {@code 2s}, {@code PT1S} and similar.
     */
    @GetMapping(path = "/prices/{pair}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter prices(@PathVariable String pair, @RequestParam Optional<String> throttle) {
        return streamService.subscribe(MarketPair.parse(pair), throttle.map(DurationStyle::detectAndParse));
    }
}
