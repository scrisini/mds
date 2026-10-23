package com.mds.grpc;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.mds.config.GrpcProperties;
import com.mds.exchange.MarketStreamManager;
import com.mds.grpc.v1.GetPriceRequest;
import com.mds.grpc.v1.ListPairsRequest;
import com.mds.grpc.v1.ListPairsResponse;
import com.mds.grpc.v1.MarketDataGrpc;
import com.mds.grpc.v1.Price;
import com.mds.grpc.v1.PriceUpdate;
import com.mds.grpc.v1.StreamEvent;
import com.mds.grpc.v1.StreamPricesRequest;
import com.mds.grpc.v1.Subscription;
import com.mds.model.MarketPair;
import com.mds.service.PairNotSubscribedException;
import com.mds.service.PriceService;
import com.mds.service.PriceStreamService;
import com.mds.service.StreamRate;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.springframework.context.event.EventListener;
import org.springframework.grpc.server.lifecycle.GrpcServerShutdownEvent;
import org.springframework.stereotype.Service;

/**
 * The {@code mds.v1.MarketData} gRPC service. Streams go through {@link PriceStreamService#open} like
 * SSE and WebSocket, so rates, the subscriber limit, metrics, heartbeats, rebalancing and shutdown
 * behave the same. Errors are mapped to statuses by {@link GrpcErrors}.
 */
@Service
public class MarketDataService extends MarketDataGrpc.MarketDataImplBase {

    /** Ends a stream so the client reconnects, through the load balancer, to another instance. */
    static final Status RECONNECT = Status.UNAVAILABLE.withDescription("rebalancing or shutting down; reconnect");

    private final PriceService prices;
    private final PriceStreamService streams;
    private final MarketStreamManager exchanges;
    private final GrpcProperties settings;
    private final Set<PriceStream> open = ConcurrentHashMap.newKeySet();

    public MarketDataService(PriceService prices, PriceStreamService streams, MarketStreamManager exchanges,
                             GrpcProperties settings) {
        this.prices = prices;
        this.streams = streams;
        this.exchanges = exchanges;
        this.settings = settings;
    }

    @Override
    public void listPairs(ListPairsRequest request, StreamObserver<ListPairsResponse> response) {
        ListPairsResponse.Builder message = ListPairsResponse.newBuilder().addAllExchanges(exchanges.ids());
        prices.pairs().forEach(pair -> message.addPairs(pair.toString()));
        response.onNext(message.build());
        response.onCompleted();
    }

    @Override
    public void getPrice(GetPriceRequest request, StreamObserver<Price> response) {
        MarketPair pair = streamed(request.getPair());
        compress(response, request.getIncludeQuotes());
        response.onNext(PriceMessages.price(prices.aggregate(pair), request.getIncludeQuotes()));
        response.onCompleted();
    }

    @Override
    public void streamPrices(StreamPricesRequest request, StreamObserver<StreamEvent> response) {
        if (request.getSubscriptionsCount() == 0) {
            throw new IllegalArgumentException("subscribe to at least one pair");
        }
        ServerCallStreamObserver<StreamEvent> call = (ServerCallStreamObserver<StreamEvent>) response;
        compress(call, request.getIncludeQuotes());
        PriceStream stream = new PriceStream(call);
        call.setOnCancelHandler(() -> close(stream));
        call.setOnReadyHandler(stream::drain);
        try {
            for (Subscription subscription : request.getSubscriptionsList()) {
                subscribe(stream, subscription, request.getIncludeQuotes());
            }
        } catch (RuntimeException e) {
            // nothing was sent: undo the subscriptions made so far, and let GrpcErrors report it
            stream.cancelled();
            throw e;
        }
        open.add(stream);
        stream.start(streams.heartbeat(stream::heartbeat, e -> close(stream)));
    }

    /** Every open stream ends with {@link #RECONNECT} before the server waits for calls to finish. */
    @EventListener(GrpcServerShutdownEvent.class)
    void endStreams() {
        open.forEach(stream -> stream.end(RECONNECT));
        open.clear();
    }

    private void subscribe(PriceStream stream, Subscription subscription, boolean includeQuotes) {
        MarketPair pair = MarketPair.parse(subscription.getPair());
        AtomicLong sequence = new AtomicLong();
        stream.add(pair, streams.open(pair, rate(subscription), PriceStreamService.GRPC,
                price -> stream.send(pair, StreamEvent.newBuilder().setPrice(PriceUpdate.newBuilder()
                        .setPair(price.pair())
                        .setSeq(sequence.incrementAndGet())
                        .setPrice(PriceMessages.price(price, includeQuotes))).build()),
                e -> close(stream),
                () -> {
                    stream.end(RECONNECT);
                    open.remove(stream);
                }));
    }

    private StreamRate rate(Subscription subscription) {
        if (subscription.getUnlimited()) {
            return streams.resolveRate(Optional.of(StreamRate.UNLIMITED), Optional.empty());
        }
        int perSecond = subscription.getRatePerSecond();
        return streams.resolveRate(perSecond == 0 ? Optional.empty() : Optional.of(Integer.toString(perSecond)),
                Optional.<Duration>empty());
    }

    private MarketPair streamed(String symbol) {
        MarketPair pair = MarketPair.parse(symbol);
        List<MarketPair> pairs = prices.pairs();
        if (!pairs.contains(pair)) {
            throw new PairNotSubscribedException(pair, pairs);
        }
        return pair;
    }

    private void close(PriceStream stream) {
        stream.cancelled();
        open.remove(stream);
    }

    /** gzip when it pays ({@code mds.grpc.compression}); a client that doesn't accept gzip gets none. */
    private void compress(StreamObserver<?> response, boolean includesQuotes) {
        if (settings.compression().applies(includesQuotes) && response instanceof ServerCallStreamObserver<?> call) {
            call.setCompression("gzip");
        }
    }
}
