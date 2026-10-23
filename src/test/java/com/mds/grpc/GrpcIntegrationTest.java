package com.mds.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.mds.exchange.MarketStreamManager;
import com.mds.grpc.v1.GetPriceRequest;
import com.mds.grpc.v1.MarketDataGrpc;
import com.mds.grpc.v1.Price;
import com.mds.model.MarketPair;
import com.mds.service.QuoteBook;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The gRPC server as the application runs it: its own port, the global interceptors, gzip. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mds.pairs=BTC/USDT",
        "mds.archive.enabled=false",
        "mds.influx.url=http://127.0.0.1:1",
        "mds.grpc.compression=gzip"})
class GrpcIntegrationTest {

    private static final Metadata.Key<String> ENCODING =
            Metadata.Key.of("grpc-encoding", Metadata.ASCII_STRING_MARSHALLER);

    @LocalGrpcServerPort
    private int port;

    @Autowired
    private QuoteBook quoteBook;

    @MockitoBean
    private MarketStreamManager streams;

    private ManagedChannel channel;

    @AfterEach
    void close() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Test
    void servesPricesGzippedWithARequestId() {
        when(streams.exchangesFor(any())).thenReturn(List.of("kraken"));
        quoteBook.onTrade("kraken", MarketPair.parse("BTC-USDT"), new BigDecimal("100"));
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
        AtomicReference<Metadata> headers = new AtomicReference<>();
        AtomicReference<Metadata> trailers = new AtomicReference<>();

        Price price = MarketDataGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newCaptureMetadataInterceptor(headers, trailers))
                .getPrice(GetPriceRequest.newBuilder().setPair("BTC-USDT").build());

        assertThat(price.getMean()).isEqualTo(10_000_000_000L);
        assertThat(headers.get().get(ENCODING)).as("the response was compressed").isEqualTo("gzip");
        assertThat(headers.get().get(GrpcRequestIds.KEY)).isNotBlank();
    }
}
