package com.mds.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.mds.config.GrpcProperties;
import com.mds.config.MdsProperties;
import com.mds.exchange.MarketStreamManager;
import com.mds.grpc.v1.GetPriceRequest;
import com.mds.grpc.v1.ListPairsRequest;
import com.mds.grpc.v1.ListPairsResponse;
import com.mds.grpc.v1.MarketDataGrpc;
import com.mds.grpc.v1.Price;
import com.mds.grpc.v1.StreamEvent;
import com.mds.grpc.v1.StreamPricesRequest;
import com.mds.grpc.v1.Subscription;
import com.mds.model.MarketPair;
import com.mds.service.PriceAggregator;
import com.mds.service.PriceService;
import com.mds.service.PriceStreamService;
import com.mds.service.QuoteBook;
import com.mds.support.TestClusters;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;

/** The service over an in-process gRPC server, with the error mapping and request ids it runs with. */
class MarketDataServiceTest {

    private static final MarketPair BTC_USDT = MarketPair.parse("BTC-USDT");
    private static final MarketPair ETH_USDT = MarketPair.parse("ETH-USDT");

    private final TestScheduler time = new TestScheduler();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QuoteBook book = new QuoteBook(Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC),
            registry);
    private PriceStreamService streams;
    private MarketDataService service;
    private Server server;
    private ManagedChannel channel;
    private MarketDataGrpc.MarketDataBlockingStub stub;

    @BeforeEach
    void start() throws Exception {
        MdsProperties properties = new MdsProperties(List.of("kraken"), List.of("BTC/USDT", "ETH/USDT"),
                Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofDays(1),
                new MdsProperties.Archive(false, Duration.ofSeconds(5)),
                new MdsProperties.Stream(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(60),
                        Duration.ofMinutes(30), 3, true, List.of("*"), Duration.ofSeconds(30)),
                new MdsProperties.DecimalSettings(8, RoundingMode.HALF_EVEN),
                new MdsProperties.Ohlc(ZoneOffset.UTC, Duration.ofSeconds(10)),
                new MdsProperties.SocksProxy("", 1080), TestClusters.standalone(), MdsProperties.Mode.DEVELOPMENT);
        MarketStreamManager exchanges = mock(MarketStreamManager.class);
        when(exchanges.exchangesFor(any())).thenReturn(List.of("kraken"));
        when(exchanges.ids()).thenReturn(List.of("kraken"));
        PriceService prices = new PriceService(exchanges, book, new PriceAggregator(), properties,
                Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC));
        streams = new PriceStreamService(book, prices, properties, registry, time, Schedulers.trampoline());
        streams.start();
        service = new MarketDataService(prices, streams, exchanges, new GrpcProperties(GrpcProperties.Compression.NONE));
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service,
                        new GrpcExceptionHandlerInterceptor(new GrpcErrors()), new GrpcRequestIds()))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = MarketDataGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void listsThePairsAndExchanges() {
        ListPairsResponse response = stub.listPairs(ListPairsRequest.getDefaultInstance());

        assertThat(response.getPairsList()).containsExactly("BTC/USDT", "ETH/USDT");
        assertThat(response.getExchangesList()).containsExactly("kraken");
    }

    @Test
    void sendsPricesAsFixedPointUnitsWithoutQuotesUnlessAsked() {
        trade(BTC_USDT, "81000.12345678");

        Price summary = stub.getPrice(GetPriceRequest.newBuilder().setPair("BTC-USDT").build());
        Price full = stub.getPrice(GetPriceRequest.newBuilder().setPair("BTC/USDT").setIncludeQuotes(true).build());

        assertThat(summary.getPair()).isEqualTo("BTC/USDT");
        assertThat(summary.getScale()).isEqualTo(8);
        assertThat(summary.getMean()).isEqualTo(8_100_012_345_678L);
        assertThat(summary.hasVwap()).as("no volume, so no VWAP").isFalse();
        assertThat(summary.getQuotesCount()).isZero();
        assertThat(full.getQuotesList()).singleElement().satisfies(quote -> {
            assertThat(quote.getExchange()).isEqualTo("kraken");
            assertThat(quote.getLast()).isEqualTo(8_100_012_345_678L);
        });
        assertThat(full.getSerializedSize()).isGreaterThan(summary.getSerializedSize());
    }

    @Test
    void mapsErrorsToStatuses() {
        assertStatus(() -> stub.getPrice(GetPriceRequest.newBuilder().setPair("DOGE-USDT").build()), Status.Code.NOT_FOUND);
        assertStatus(() -> stub.getPrice(GetPriceRequest.newBuilder().setPair("BTCUSDT").build()), Status.Code.INVALID_ARGUMENT);
        assertStatus(() -> stub.getPrice(GetPriceRequest.newBuilder().setPair("BTC-USDT").build()), Status.Code.UNAVAILABLE);
        assertStatus(() -> stub.streamPrices(StreamPricesRequest.getDefaultInstance()).hasNext(),
                Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void returnsTheCallersRequestIdOrANewOne() {
        trade(BTC_USDT, "100");
        AtomicReference<Metadata> headers = new AtomicReference<>();
        AtomicReference<Metadata> trailers = new AtomicReference<>();
        Metadata supplied = new Metadata();
        supplied.put(GrpcRequestIds.KEY, "client-42");

        stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(supplied),
                        MetadataUtils.newCaptureMetadataInterceptor(headers, trailers))
                .getPrice(GetPriceRequest.newBuilder().setPair("BTC-USDT").build());
        assertThat(headers.get().get(GrpcRequestIds.KEY)).isEqualTo("client-42");

        assertStatus(() -> stub.withInterceptors(MetadataUtils.newCaptureMetadataInterceptor(headers, trailers))
                .getPrice(GetPriceRequest.newBuilder().setPair("DOGE-USDT").build()), Status.Code.NOT_FOUND);
        assertThat(trailers.get().get(GrpcRequestIds.KEY)).as("generated, in the trailers of a failed call")
                .isNotBlank().isNotEqualTo("client-42");
    }

    @Test
    void streamsSeveralPairsEachAtItsRate() {
        trade(BTC_USDT, "100");
        trade(ETH_USDT, "2000");
        Iterator<StreamEvent> events = stub.streamPrices(StreamPricesRequest.newBuilder()
                .addSubscriptions(Subscription.newBuilder().setPair("BTC-USDT").setUnlimited(true))
                .addSubscriptions(Subscription.newBuilder().setPair("ETH-USDT").setRatePerSecond(1))
                .build());

        assertThat(List.of(events.next(), events.next())).extracting(e -> e.getPrice().getPair())
                .containsExactlyInAnyOrder("BTC/USDT", "ETH/USDT");
        trade(BTC_USDT, "101");
        StreamEvent next = events.next();
        assertThat(next.getPrice().getPair()).isEqualTo("BTC/USDT");
        assertThat(next.getPrice().getSeq()).isEqualTo(2);
        assertThat(next.getPrice().getPrice().getMean()).isEqualTo(10_100_000_000L);
        assertThat(streams.subscribers()).isEqualTo(2);
        assertThat(registry.get("mds.stream.events").tag("transport", "grpc").counters()).isNotEmpty();
    }

    @Test
    void sendsAHeartbeatOnAQuietStream() {
        trade(BTC_USDT, "100");
        Iterator<StreamEvent> events = stub.streamPrices(StreamPricesRequest.newBuilder()
                .addSubscriptions(Subscription.newBuilder().setPair("BTC-USDT")).build());
        events.next();

        time.advanceTimeBy(30, TimeUnit.SECONDS);

        assertThat(events.next().hasHeartbeat()).isTrue();
    }

    @Test
    void refusesStreamsBeyondTheSubscriberLimitAndFreesSlotsOnCancel() {
        trade(BTC_USDT, "100");
        StreamPricesRequest three = StreamPricesRequest.newBuilder()
                .addSubscriptions(Subscription.newBuilder().setPair("BTC-USDT"))
                .addSubscriptions(Subscription.newBuilder().setPair("ETH-USDT"))
                .addSubscriptions(Subscription.newBuilder().setPair("BTC-USDT").setRatePerSecond(2))
                .build();
        MarketDataGrpc.MarketDataStub async = MarketDataGrpc.newStub(channel);
        io.grpc.stub.StreamObserver<StreamEvent> ignore = new io.grpc.stub.StreamObserver<>() {
            public void onNext(StreamEvent value) { }
            public void onError(Throwable t) { }
            public void onCompleted() { }
        };
        io.grpc.Context.CancellableContext first = io.grpc.Context.current().withCancellation();
        first.run(() -> async.streamPrices(StreamPricesRequest.newBuilder()
                .addSubscriptions(Subscription.newBuilder().setPair("BTC-USDT"))
                .addSubscriptions(Subscription.newBuilder().setPair("ETH-USDT")).build(), ignore));
        assertThat(streams.subscribers()).isEqualTo(2);

        assertStatus(() -> stub.streamPrices(three).hasNext(), Status.Code.RESOURCE_EXHAUSTED);
        assertThat(streams.subscribers()).as("the refused call's subscriptions were undone").isEqualTo(2);

        first.cancel(null);
        assertThat(streams.subscribers()).isZero();
    }

    @Test
    void endsStreamsWithUnavailableWhenRebalancingOrShuttingDown() {
        trade(BTC_USDT, "100");
        Iterator<StreamEvent> shed = stub.streamPrices(StreamPricesRequest.newBuilder()
                .addSubscriptions(Subscription.newBuilder().setPair("BTC-USDT")).build());
        shed.next();
        streams.shed(1);
        assertStatus(shed::next, Status.Code.UNAVAILABLE);

        Iterator<StreamEvent> stopping = stub.streamPrices(StreamPricesRequest.newBuilder()
                .addSubscriptions(Subscription.newBuilder().setPair("BTC-USDT")).build());
        stopping.next();
        service.endStreams();
        assertStatus(stopping::next, Status.Code.UNAVAILABLE);
        assertThat(streams.subscribers()).isZero();
    }

    private static void assertStatus(Runnable call, Status.Code code) {
        assertThatThrownBy(call::run).isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(code));
    }

    private void trade(MarketPair pair, String price) {
        book.onTrade("kraken", pair, new BigDecimal(price));
    }
}
