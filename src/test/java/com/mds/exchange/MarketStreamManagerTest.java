package com.mds.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import com.mds.config.MdsProperties;
import com.mds.math.Decimal;
import com.mds.model.MarketPair;
import com.mds.service.QuoteBook;
import com.mds.support.TestClusters;
import info.bitrich.xchangestream.core.ProductSubscription;
import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.core.StreamingMarketDataService;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.dto.marketdata.Trade;

@SuppressWarnings("deprecation") // stubs getTicker/getTrades(CurrencyPair), see MarketStreamManager#subscribe
class MarketStreamManagerTest {

    private static final MarketPair BTC_USD = MarketPair.parse("BTC-USD");

    private final QuoteBook book = new QuoteBook(Clock.systemUTC());
    private final StreamingExchange exchange = mock(StreamingExchange.class);
    private final StreamingMarketDataService marketData = mock(StreamingMarketDataService.class);
    private final PublishSubject<Ticker> tickers = PublishSubject.create();
    private MarketStreamManager manager;

    @BeforeEach
    void setUp() {
        MdsProperties properties = new MdsProperties(List.of(), List.of("BTC/USD"),
                Duration.ofSeconds(10), Duration.ofMillis(20), Duration.ofSeconds(60),
                new MdsProperties.Archive(false, Duration.ofSeconds(5)),
                new MdsProperties.Stream(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(60),
                        Duration.ofMinutes(30), 2, true, List.of("*"), Duration.ofSeconds(30)),
                new MdsProperties.DecimalSettings(8, RoundingMode.HALF_EVEN),
                new MdsProperties.Ohlc(ZoneOffset.UTC, Duration.ofSeconds(10)),
                new MdsProperties.SocksProxy("", 1080), TestClusters.standalone());
        manager = new MarketStreamManager(properties, book);
        when(exchange.getStreamingMarketDataService()).thenReturn(marketData);
        when(exchange.disconnect()).thenReturn(Completable.complete());
    }

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    @Test
    void feedsTickerUpdatesIntoQuoteBook() {
        when(exchange.connect(any(ProductSubscription[].class))).thenReturn(Completable.complete());
        when(marketData.getTicker(any(CurrencyPair.class))).thenReturn(tickers);

        manager.attach("kraken", exchange, List.of(BTC_USD), false);
        tickers.onNext(new Ticker.Builder().instrument(BTC_USD.toCurrencyPair()).last(new BigDecimal("100")).build());

        assertThat(book.latest("kraken", BTC_USD)).hasValueSatisfying(
                q -> assertThat(q.last()).isEqualTo(Decimal.parse("100")));
        assertThat(manager.exchangesFor(BTC_USD)).containsExactly("kraken");
        verify(marketData, never()).getTrades(any(CurrencyPair.class));
    }

    @Test
    void retriesFailedConnection() {
        when(exchange.connect(any(ProductSubscription[].class)))
                .thenReturn(Completable.error(new RuntimeException("handshake failed")), Completable.complete());
        when(marketData.getTicker(any(CurrencyPair.class))).thenReturn(tickers);

        manager.attach("kraken", exchange, List.of(BTC_USD), false);

        await().atMost(Duration.ofSeconds(2)).until(tickers::hasObservers);
    }

    @Test
    void resubscribesWhenStreamErrors() {
        when(exchange.connect(any(ProductSubscription[].class))).thenReturn(Completable.complete());
        when(marketData.getTicker(any(CurrencyPair.class)))
                .thenReturn(Observable.error(new RuntimeException("channel closed")), tickers);

        manager.attach("kraken", exchange, List.of(BTC_USD), false);

        await().atMost(Duration.ofSeconds(2)).until(tickers::hasObservers);
    }

    @Test
    void resubscribesWhenStreamCompletes() {
        when(exchange.connect(any(ProductSubscription[].class))).thenReturn(Completable.complete());
        when(marketData.getTicker(any(CurrencyPair.class))).thenReturn(Observable.empty(), tickers);

        manager.attach("kraken", exchange, List.of(BTC_USD), false);

        await().atMost(Duration.ofSeconds(2)).until(tickers::hasObservers);
    }

    @Test
    void usesTradesForLastPriceWhenFlagged() {
        PublishSubject<Trade> trades = PublishSubject.create();
        when(exchange.connect(any(ProductSubscription[].class))).thenReturn(Completable.complete());
        when(marketData.getTicker(any(CurrencyPair.class))).thenReturn(tickers);
        when(marketData.getTrades(any(CurrencyPair.class))).thenReturn(trades);

        manager.attach("bitstamp", exchange, List.of(BTC_USD), true);
        tickers.onNext(new Ticker.Builder().instrument(BTC_USD.toCurrencyPair()).bid(new BigDecimal("99")).build());
        trades.onNext(Trade.builder().type(Order.OrderType.BID).instrument(BTC_USD.toCurrencyPair())
                .price(new BigDecimal("100.5")).originalAmount(BigDecimal.ONE).build());

        assertThat(book.latest("bitstamp", BTC_USD)).hasValueSatisfying(q -> {
            assertThat(q.last()).isEqualTo(Decimal.parse("100.5"));
            assertThat(q.bid()).isEqualTo(Decimal.parse("99"));
        });
    }
}
