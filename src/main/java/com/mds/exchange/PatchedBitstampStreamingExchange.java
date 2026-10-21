package com.mds.exchange;

import info.bitrich.xchangestream.bitstamp.v2.BitstampStreamingMarketDataService;
import info.bitrich.xchangestream.bitstamp.v2.BitstampStreamingService;
import info.bitrich.xchangestream.core.ProductSubscription;
import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.core.StreamingMarketDataService;
import info.bitrich.xchangestream.service.netty.ConnectionStateModel;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.bitstamp.BitstampExchange;

/**
 * Drop-in replacement for XChange 6.0.0's {@code BitstampStreamingExchange}, which never
 * delivers any market data: its subscriptions are keyed by channel name plus argument
 * ({@code order_book_btcusd_data}) while incoming messages are matched on the bare channel
 * name ({@code order_book_btcusd}), so every message is dropped as coming from a
 * "disconnected channel". Keying subscriptions by channel name alone fixes the lookup.
 *
 * <p>Remove once the upstream module is fixed.
 */
public class PatchedBitstampStreamingExchange extends BitstampExchange implements StreamingExchange {

    private static final String API_URI = "wss://ws.bitstamp.net";

    private final BitstampStreamingService streamingService = new ChannelKeyedStreamingService(API_URI);
    private BitstampStreamingMarketDataService streamingMarketDataService;

    @Override
    protected void initServices() {
        super.initServices();
        applyStreamingSpecification(getExchangeSpecification(), streamingService);
        streamingMarketDataService = new BitstampStreamingMarketDataService(streamingService);
    }

    @Override
    public Completable connect(ProductSubscription... args) {
        return streamingService.connect();
    }

    @Override
    public Completable disconnect() {
        return streamingService.disconnect();
    }

    @Override
    public boolean isAlive() {
        return streamingService.isSocketOpen();
    }

    @Override
    public Observable<Throwable> reconnectFailure() {
        return streamingService.subscribeReconnectFailure();
    }

    @Override
    public Observable<Object> connectionSuccess() {
        return streamingService.subscribeConnectionSuccess();
    }

    @Override
    public Observable<ConnectionStateModel.State> connectionStateObservable() {
        return streamingService.subscribeConnectionState();
    }

    @Override
    public ExchangeSpecification getDefaultExchangeSpecification() {
        ExchangeSpecification spec = super.getDefaultExchangeSpecification();
        spec.setShouldLoadRemoteMetaData(false);
        return spec;
    }

    @Override
    public StreamingMarketDataService getStreamingMarketDataService() {
        return streamingMarketDataService;
    }

    @Override
    public void useCompressedMessages(boolean compressedMessages) {
        streamingService.useCompressedMessages(compressedMessages);
    }

    private static final class ChannelKeyedStreamingService extends BitstampStreamingService {

        ChannelKeyedStreamingService(String apiUrl) {
            super(apiUrl);
        }

        @Override
        public String getSubscriptionUniqueId(String channelName, Object... args) {
            return channelName;
        }
    }
}
