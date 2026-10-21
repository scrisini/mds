package com.mds.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;

import java.math.RoundingMode;
import java.net.Proxy;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.mds.config.MdsProperties;
import com.mds.service.QuoteBook;
import com.mds.support.FakeSocks5Server;
import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.core.ProductSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.client.ExchangeRestProxyBuilder;
import org.knowm.xchange.currency.CurrencyPair;
import si.mazi.rescu.ClientConfig;

class SocksProxyTest {

    private static final MdsProperties.SocksProxy PROXY = new MdsProperties.SocksProxy("socks.internal", 1081);

    @Test
    void restCallsDefaultToSocksFromRescuProperties() {
        assertThat(new ClientConfig().getProxyType()).isEqualTo(Proxy.Type.SOCKS);
    }

    @Test
    void configuresRestAndWebSocketProxyOnTheSpecification() {
        ExchangeSpecification spec = new ExchangeSpecification(StreamingExchange.class);

        MarketStreamManager.configure(spec, properties(PROXY));

        ClientConfig rest = ExchangeRestProxyBuilder.createClientConfig(spec);
        assertThat(rest.getProxyHost()).isEqualTo("socks.internal");
        assertThat(rest.getProxyPort()).isEqualTo(1081);
        assertThat(rest.getProxyType()).isEqualTo(Proxy.Type.SOCKS);
        assertThat(spec.getExchangeSpecificParametersItem(StreamingExchange.SOCKS_PROXY_HOST)).isEqualTo("socks.internal");
        assertThat(spec.getExchangeSpecificParametersItem(StreamingExchange.SOCKS_PROXY_PORT)).isEqualTo(1081);
    }

    @Test
    void leavesConnectionsDirectWhenNoProxyIsConfigured() {
        ExchangeSpecification spec = new ExchangeSpecification(StreamingExchange.class);

        MarketStreamManager.configure(spec, properties(new MdsProperties.SocksProxy("", 1080)));

        assertThat(ExchangeRestProxyBuilder.createClientConfig(spec).getProxyHost()).isNull();
        assertThat(spec.getExchangeSpecificParametersItem(StreamingExchange.SOCKS_PROXY_HOST)).isNull();
    }

    /**
     * End to end on the wire: every supported exchange, on connect, talks SOCKS5 to the configured
     * proxy rather than connecting directly. The fake proxy refuses each request, so nothing
     * reaches the exchange. (KuCoin's first connection is its REST token request, so this also
     * covers rescu.) Fails if an exchange module, or a future XChange version, ignores the settings.
     */
    @ParameterizedTest
    @EnumSource(SupportedExchange.class)
    void everyExchangeConnectsThroughTheProxy(SupportedExchange supported) throws Exception {
        try (FakeSocks5Server proxy = new FakeSocks5Server()) {
            StreamingExchange exchange = (StreamingExchange) ExchangeFactory.INSTANCE
                    .createExchangeWithoutSpecification(supported.exchangeClass());
            ExchangeSpecification spec = exchange.getDefaultExchangeSpecification();
            spec.setShouldLoadRemoteMetaData(false);
            spec.setExchangeSpecificParametersItem(StreamingExchange.AUTO_RECONNECT, false);
            MarketStreamManager.configure(spec,
                    properties(new MdsProperties.SocksProxy("127.0.0.1", proxy.port())));
            exchange.applySpecification(spec);

            Throwable failure = null;
            try {
                exchange.connect(ProductSubscription.create().addTicker(CurrencyPair.BTC_USDT).build())
                        .timeout(15, TimeUnit.SECONDS)
                        .blockingAwait();
            } catch (RuntimeException e) {
                failure = e;
            }

            assertThat(failure).as("%s must not connect while its proxy refuses", supported).isNotNull();
            assertThat(proxy.destinations()).as("%s connections seen by the proxy", supported).isNotEmpty();
            try {
                exchange.disconnect().blockingAwait(2, TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                // never connected
            }
        }
    }

    @Test
    void refusesToStartWhenRestCallsWouldNotUseSocks() {
        MarketStreamManager manager = new MarketStreamManager(properties(PROXY), mock(QuoteBook.class));

        assertThatIllegalStateException().isThrownBy(() -> manager.verifyProxySupport(Proxy.Type.HTTP))
                .withMessageContaining("rescu.http.readProxyType=SOCKS");
        assertThatNoException().isThrownBy(() -> manager.verifyProxySupport(Proxy.Type.SOCKS));
    }

    @Test
    void noProxyNeedsNoSocksSupport() {
        MarketStreamManager manager = new MarketStreamManager(properties(new MdsProperties.SocksProxy(null, 1080)),
                mock(QuoteBook.class));

        assertThatNoException().isThrownBy(() -> manager.verifyProxySupport(Proxy.Type.HTTP));
    }

    @Test
    void validatesPort() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MdsProperties.SocksProxy("socks", 0));
        assertThat(new MdsProperties.SocksProxy("  ", 0).enabled()).isFalse();
        assertThat(PROXY).hasToString("socks.internal:1081");
    }

    private static MdsProperties properties(MdsProperties.SocksProxy proxy) {
        return new MdsProperties(List.of(), List.of("BTC/USDT"), Duration.ofSeconds(10), Duration.ofSeconds(5),
                Duration.ofSeconds(60), new MdsProperties.Archive(false, Duration.ofSeconds(5)),
                new MdsProperties.Stream(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(60),
                        Duration.ofMinutes(30), 10),
                new MdsProperties.DecimalSettings(8, RoundingMode.HALF_EVEN),
                new MdsProperties.Ohlc(ZoneOffset.UTC, Duration.ofSeconds(10)),
                proxy);
    }
}
