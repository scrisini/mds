package com.mds.load;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.mds.grpc.v1.MarketDataGrpc;
import com.mds.grpc.v1.StreamEvent;
import com.mds.grpc.v1.StreamPricesRequest;
import com.mds.grpc.v1.Subscription;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.stub.StreamObserver;

/**
 * Opens many {@code StreamPrices} subscriptions against a running instance and reports what arrived:
 * events per second, and bytes per second both on the wire (compressed, if the server compresses)
 * and as messages. The gRPC counterpart of {@code scripts/load-sse.mjs}, for README "Capacity".
 *
 * <pre>
 * ./gradlew grpcLoad -Pargs="&lt;subscribers&gt; &lt;rate|unlimited&gt; &lt;seconds&gt; [host:port] [quotes] [connections]"
 * ./gradlew grpcLoad -Pargs="1000 10 60 localhost:9090 false 1000"
 * </pre>
 *
 * Each subscription takes one pair, spread evenly over {@link #PAIRS}. By default every subscriber
 * has its own connection, like the SSE script; fewer connections multiplex streams over HTTP/2.
 */
public final class GrpcLoad {

    private static final String[] PAIRS = {"BTC-USDT", "ETH-USDT", "BTC-USD", "ETH-USD"};

    private GrpcLoad() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 3) {
            System.err.println("usage: <subscribers> <rate|unlimited> <seconds> [host:port] [quotes] [connections]");
            System.exit(2);
        }
        int subscribers = Integer.parseInt(args[0]);
        String rate = args[1];
        int seconds = Integer.parseInt(args[2]);
        String target = args.length > 3 ? args[3] : "localhost:9090";
        boolean quotes = args.length > 4 && Boolean.parseBoolean(args[4]);
        int connections = args.length > 5 ? Integer.parseInt(args[5]) : subscribers;

        AtomicLong events = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        AtomicLong wireBytes = new AtomicLong();
        AtomicLong messageBytes = new AtomicLong();
        ClientStreamTracer.Factory tracer = new ClientStreamTracer.Factory() {
            @Override
            public ClientStreamTracer newClientStreamTracer(ClientStreamTracer.StreamInfo info, Metadata headers) {
                return new ClientStreamTracer() {
                    @Override
                    public void inboundWireSize(long bytes) {
                        wireBytes.addAndGet(bytes);
                    }

                    @Override
                    public void inboundUncompressedSize(long bytes) {
                        messageBytes.addAndGet(bytes);
                    }
                };
            }
        };
        ClientInterceptor traced = new ClientInterceptor() {
            @Override
            public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options,
                                                         Channel next) {
                return next.newCall(method, options.withStreamTracerFactory(tracer));
            }
        };
        List<ManagedChannel> channels = new ArrayList<>();
        for (int i = 0; i < connections; i++) {
            channels.add(ManagedChannelBuilder.forTarget(target).usePlaintext().build());
        }
        for (int i = 0; i < subscribers; i++) {
            Subscription.Builder subscription = Subscription.newBuilder().setPair(PAIRS[i % PAIRS.length]);
            if (rate.equals("unlimited")) {
                subscription.setUnlimited(true);
            } else {
                subscription.setRatePerSecond(Integer.parseInt(rate));
            }
            MarketDataGrpc.newStub(channels.get(i % connections)).withInterceptors(traced)
                    .streamPrices(StreamPricesRequest.newBuilder().addSubscriptions(subscription)
                            .setIncludeQuotes(quotes).build(), new StreamObserver<StreamEvent>() {
                        @Override
                        public void onNext(StreamEvent event) {
                            if (event.hasPrice()) {
                                events.incrementAndGet();
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            errors.incrementAndGet();
                        }

                        @Override
                        public void onCompleted() {
                        }
                    });
        }
        // count a steady state: from one second in, once the streams are open
        TimeUnit.SECONDS.sleep(1);
        long events0 = events.get();
        long wire0 = wireBytes.get();
        long message0 = messageBytes.get();
        TimeUnit.SECONDS.sleep(seconds);
        long count = events.get() - events0;
        System.out.printf("{\"subscribers\":%d,\"rate\":\"%s\",\"quotes\":%b,\"connections\":%d,\"errors\":%d,"
                        + "\"eventsPerSec\":%.1f,\"wireKbPerSec\":%.1f,\"messageKbPerSec\":%.1f,"
                        + "\"wireBytesPerEvent\":%.1f,\"messageBytesPerEvent\":%.1f}%n",
                subscribers, rate, quotes, connections, errors.get(), (double) count / seconds,
                (wireBytes.get() - wire0) / 1024.0 / seconds, (messageBytes.get() - message0) / 1024.0 / seconds,
                count == 0 ? 0 : (double) (wireBytes.get() - wire0) / count,
                count == 0 ? 0 : (double) (messageBytes.get() - message0) / count);
        channels.forEach(ManagedChannel::shutdownNow);
    }
}
