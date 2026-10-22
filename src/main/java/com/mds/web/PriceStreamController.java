package com.mds.web;

import java.util.Optional;

import com.mds.model.MarketPair;
import com.mds.service.PriceStreamService;
import com.mds.service.StreamRate;
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
     * Live aggregate for one pair, e.g. {@code /api/v1/stream/prices/BTC-USDT?rate=2}.
     * {@code rate} is updates per second ({@code 1}, {@code 2}, {@code 3}…) or {@code unlimited};
     * alternatively {@code throttle} gives a minimum interval ({@code 250ms}, {@code 2s}, {@code PT1S}).
     */
    @GetMapping(path = "/prices/{pair}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter prices(@PathVariable String pair, @RequestParam Optional<String> rate,
                             @RequestParam Optional<String> throttle) {
        StreamRate streamRate = streamService.resolveRate(rate, throttle.map(DurationStyle::detectAndParse));
        return streamService.subscribe(MarketPair.parse(pair), streamRate);
    }
}
