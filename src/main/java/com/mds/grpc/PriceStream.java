package com.mds.grpc;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mds.grpc.v1.Heartbeat;
import com.mds.grpc.v1.StreamEvent;
import com.mds.model.MarketPair;
import com.mds.service.StreamSubscription;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * One {@code StreamPrices} call: its subscriptions (one per pair), its heartbeat and the client's flow
 * control. Sends are serialized, because the subscriptions and the heartbeat send from threads of
 * their own and a {@code StreamObserver} isn't thread-safe. While the client isn't reading, only the
 * latest event per pair is kept, then sent once it reads again: memory stays bounded by the pairs,
 * whatever the client does. Nothing is sent before {@link #start}, so a request that fails on its
 * second subscription doesn't first send prices for its first.
 */
final class PriceStream {

    private static final StreamEvent HEARTBEAT = StreamEvent.newBuilder()
            .setHeartbeat(Heartbeat.getDefaultInstance()).build();

    private final ServerCallStreamObserver<StreamEvent> call;
    private final Map<MarketPair, StreamSubscription> subscriptions = new ConcurrentHashMap<>();
    // guarded by this
    private final Map<MarketPair, StreamEvent> pending = new LinkedHashMap<>();
    private Disposable heartbeat = Disposable.disposed();
    private boolean started;
    private boolean closed;

    PriceStream(ServerCallStreamObserver<StreamEvent> call) {
        this.call = call;
    }

    void add(MarketPair pair, StreamSubscription subscription) {
        StreamSubscription replaced = subscriptions.put(pair, subscription);
        if (replaced != null) {
            replaced.close();
        }
    }

    /** Every subscription is open: sends what arrived meanwhile, and keeps the stream alive from now on. */
    synchronized void start(Disposable heartbeat) {
        if (closed) {
            heartbeat.dispose();
            return;
        }
        this.heartbeat = heartbeat;
        started = true;
        drain();
    }

    /** Sends the event now, or keeps it as the pair's latest until the client reads again. */
    synchronized void send(MarketPair pair, StreamEvent event) {
        if (closed) {
            return;
        }
        if (started && call.isReady()) {
            call.onNext(event);
        } else {
            pending.put(pair, event);
        }
    }

    /** The client can read again: sends what's pending, oldest pair first. */
    synchronized void drain() {
        Iterator<StreamEvent> events = pending.values().iterator();
        while (started && !closed && call.isReady() && events.hasNext()) {
            call.onNext(events.next());
            events.remove();
        }
    }

    /** Skipped while the client isn't reading: pending prices will keep the connection busy anyway. */
    synchronized void heartbeat() {
        if (started && !closed && call.isReady()) {
            call.onNext(HEARTBEAT);
        }
    }

    /** Ends the call with this status, e.g. UNAVAILABLE so the client reconnects elsewhere. */
    synchronized void end(Status status) {
        if (release()) {
            call.onError(status.asRuntimeException());
        }
    }

    /** The client went away; nothing more can be sent. */
    synchronized void cancelled() {
        release();
    }

    /** Closes the subscriptions and the heartbeat, once. Returns whether this call did it. */
    private boolean release() {
        if (closed) {
            return false;
        }
        closed = true;
        pending.clear();
        heartbeat.dispose();
        subscriptions.values().forEach(StreamSubscription::close);
        return true;
    }
}
