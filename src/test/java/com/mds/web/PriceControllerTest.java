package com.mds.web;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.mds.model.AggregatedPrice;
import com.mds.model.MarketPair;
import com.mds.model.QuoteFailure;
import com.mds.persistence.PriceRepository;
import com.mds.service.PairNotSubscribedException;
import com.mds.service.PriceService;
import com.mds.service.PriceUnavailableException;
import com.mds.service.UnknownExchangeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PriceController.class)
class PriceControllerTest {

    private static final MarketPair BTC_USDT = MarketPair.parse("BTC-USDT");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PriceService priceService;

    @MockitoBean
    private PriceRepository repository;

    @Test
    void returnsLiveAggregate() throws Exception {
        BigDecimal price = new BigDecimal("67000.5");
        when(priceService.aggregate(BTC_USDT)).thenReturn(new AggregatedPrice("BTC/USDT",
                Instant.parse("2026-10-21T12:00:00Z"), 1, price, price, null, price, price,
                null, null, null, null, List.of(), List.of()));

        mvc.perform(get("/api/v1/prices/btc-usdt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pair", is("BTC/USDT")))
                .andExpect(jsonPath("$.mean", is(67000.5)))
                .andExpect(jsonPath("$.timestamp", is("2026-10-21T12:00:00Z")));
    }

    @Test
    void malformedPairIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/prices/BTCUSDT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsubscribedPairIsNotFound() throws Exception {
        MarketPair doge = MarketPair.parse("DOGE-USDT");
        when(priceService.aggregate(doge)).thenThrow(new PairNotSubscribedException(doge, List.of(BTC_USDT)));

        mvc.perform(get("/api/v1/prices/DOGE-USDT"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownExchangeIsNotFound() throws Exception {
        when(priceService.quote("nope", BTC_USDT)).thenThrow(new UnknownExchangeException("nope"));

        mvc.perform(get("/api/v1/prices/BTC-USDT/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unavailablePriceListsFailures() throws Exception {
        when(priceService.aggregate(BTC_USDT)).thenThrow(
                new PriceUnavailableException(BTC_USDT, List.of(new QuoteFailure("kraken", "timed out"))));

        mvc.perform(get("/api/v1/prices/BTC-USDT"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.failures[0].exchange", is("kraken")));
    }
}
