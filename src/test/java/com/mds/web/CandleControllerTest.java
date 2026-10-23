package com.mds.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.mds.exchange.MarketStreamManager;
import com.mds.model.CandlePeriod;
import com.mds.model.MarketPair;
import com.mds.persistence.PriceRepository;
import com.mds.service.CandleService;
import com.mds.service.PriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CandleController.class)
@Import(CandleControllerTest.FixedClock.class)
class CandleControllerTest {

    private static final MarketPair BTC_USDT = MarketPair.parse("BTC-USDT");
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CandleService candles;

    @MockitoBean
    private PriceRepository repository;

    @MockitoBean
    private PriceService priceService;

    @MockitoBean
    private MarketStreamManager streams;

    @BeforeEach
    void setUp() {
        when(priceService.pairs()).thenReturn(List.of(BTC_USDT));
        when(candles.zone()).thenReturn(ZoneOffset.UTC);
    }

    @Test
    void defaultsToTheLookbackBeforeTheClockFromAPeriodStart() throws Exception {
        mvc.perform(get("/api/v1/candles/BTC-USDT?period=day")).andExpect(status().isOk());

        // 30 days before the clock, from the start of that day
        verify(repository).findCandles(BTC_USDT, CandleService.INDEX, CandlePeriod.DAY,
                Instant.parse("2026-09-21T00:00:00Z"), NOW, 1000);
    }

    @Test
    void aFromAfterTheClockIsRejected() throws Exception {
        mvc.perform(get("/api/v1/candles/BTC-USDT?from=2026-10-22T00:00:00Z")).andExpect(status().isBadRequest());
    }
}
