package com.mds.web;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.mds.model.MarketPair;
import com.mds.persistence.PriceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HistoryController.class)
@Import(HistoryControllerTest.FixedClock.class)
class HistoryControllerTest {

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
    private PriceRepository repository;

    @Test
    void defaultsToTheHourBeforeTheClock() throws Exception {
        mvc.perform(get("/api/v1/history/BTC-USDT")).andExpect(status().isOk());

        verify(repository).findAggregates(BTC_USDT, NOW.minusSeconds(3600), NOW, Optional.empty(), 1000);
    }

    @Test
    void tickersDefaultToTheHourBeforeTheClock() throws Exception {
        mvc.perform(get("/api/v1/history/BTC-USDT/tickers?exchange=Kraken")).andExpect(status().isOk());

        verify(repository).findTickers(BTC_USDT, Optional.of("kraken"), NOW.minusSeconds(3600), NOW,
                Optional.empty(), 1000);
    }

    @Test
    void aFromAfterTheClockIsRejected() throws Exception {
        mvc.perform(get("/api/v1/history/BTC-USDT?from=2026-10-21T13:00:00Z")).andExpect(status().isBadRequest());
    }
}
