package com.mds.grpc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mds.grpc.v1.PriceUpdate;
import com.mds.grpc.v1.StreamEvent;
import com.mds.model.MarketPair;
import com.mds.service.StreamSubscription;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.reactivex.rxjava3.disposables.Disposable;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PriceStreamTest {

    private static final MarketPair BTC = MarketPair.parse("BTC-USDT");
    private static final MarketPair ETH = MarketPair.parse("ETH-USDT");

    @SuppressWarnings("unchecked")
    private final ServerCallStreamObserver<StreamEvent> call = mock(ServerCallStreamObserver.class);
    private final PriceStream stream = new PriceStream(call);

    @Test
    void keepsOnlyTheLatestEventPerPairWhileTheClientIsntReading() {
        stream.start(Disposable.empty());
        when(call.isReady()).thenReturn(false);
        stream.send(BTC, event(1));
        stream.send(BTC, event(2));
        stream.send(ETH, event(3));
        verify(call, never()).onNext(any());

        when(call.isReady()).thenReturn(true);
        stream.drain();

        InOrder sent = inOrder(call);
        sent.verify(call).onNext(event(2));
        sent.verify(call).onNext(event(3));
        verify(call, times(2)).onNext(any());
    }

    @Test
    void sendsNothingUntilEverySubscriptionIsOpen() {
        when(call.isReady()).thenReturn(true);
        stream.send(BTC, event(1));
        verify(call, never()).onNext(any());

        stream.start(Disposable.empty());

        verify(call).onNext(event(1));
    }

    @Test
    void endingClosesEverySubscriptionOnceAndSendsNothingAfterwards() {
        StreamSubscription subscription = mock(StreamSubscription.class);
        stream.add(BTC, subscription);
        when(call.isReady()).thenReturn(true);
        stream.start(Disposable.empty());

        stream.end(Status.UNAVAILABLE);
        stream.end(Status.UNAVAILABLE);
        stream.send(BTC, event(1));

        verify(subscription).close();
        verify(call).onError(any());
        verify(call, never()).onNext(any());
    }

    private static StreamEvent event(long seq) {
        return StreamEvent.newBuilder().setPrice(PriceUpdate.newBuilder().setSeq(seq)).build();
    }
}
