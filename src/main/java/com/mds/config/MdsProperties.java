package com.mds.config;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Aggregator settings bound from the {@code mds.*} namespace.
 *
 * @param exchanges         exchange identifiers to stream from (see {@link com.mds.exchange.SupportedExchange})
 * @param pairs             currency pairs subscribed on every exchange that lists them, e.g. {@code BTC/USDT}
 * @param connectionTimeout HTTP timeout for the metadata calls made while connecting to an exchange
 * @param reconnectDelay    wait before retrying a failed connection or a terminated stream
 * @param staleAfter        quotes older than this are left out of aggregates
 * @param archive           snapshot archiving settings
 * @param stream            client SSE subscription settings
 * @param decimal           precision of all prices and volumes
 * @param ohlc              OHLC candle settings
 * @param socksProxy        SOCKS5 proxy for all outgoing exchange traffic
 * @param cluster           Hazelcast cluster that shares candles and balances subscribers across instances
 * @param mode              {@code production} refuses settings that are unsafe there ({@link ProductionSafeguards})
 */
@ConfigurationProperties("mds")
public record MdsProperties(
        @DefaultValue({"binance", "kraken", "bitstamp", "kucoin", "bitfinex"}) List<String> exchanges,
        @DefaultValue({"BTC/USDT", "ETH/USDT"}) List<String> pairs,
        @DefaultValue("10s") Duration connectionTimeout,
        @DefaultValue("5s") Duration reconnectDelay,
        @DefaultValue("60s") Duration staleAfter,
        @DefaultValue Archive archive,
        @DefaultValue Stream stream,
        @DefaultValue DecimalSettings decimal,
        @DefaultValue Ohlc ohlc,
        @DefaultValue SocksProxy socksProxy,
        @DefaultValue Cluster cluster,
        @DefaultValue("development") Mode mode) {

    /** Where the instance runs. Production refuses unsafe settings; development allows local defaults. */
    public enum Mode {
        DEVELOPMENT, PRODUCTION;

        /** As configured and shown: {@code development}, {@code production}. */
        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @param enabled  whether snapshots of the live quotes are written to InfluxDB
     * @param interval time between snapshots
     */
    public record Archive(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") Duration interval) {
    }

    /**
     * Real-time price subscriptions over Server-Sent Events and WebSocket.
     *
     * @param defaultThrottle  minimum time between events when the client doesn't ask for a rate
     * @param minThrottle      shortest interval a client may request; also caps {@code rate} (100ms = 10/s)
     * @param maxThrottle      longest interval a client may request
     * @param timeout          SSE connection lifetime, and WebSocket idle timeout
     * @param maxSubscribers   concurrent subscriptions across all clients (an SSE connection, or one
     *                         pair on a WebSocket)
     * @param allowUnthrottled whether clients may request {@code rate=unlimited}
     * @param allowedOrigins   browser origins allowed to open WebSockets ({@code *} for any)
     * @param heartbeat        how often every open stream gets a heartbeat (an SSE comment, a
     *                         WebSocket ping), so load balancers and proxies keep quiet connections
     *                         open; zero turns it off
     */
    public record Stream(
            @DefaultValue("1s") Duration defaultThrottle,
            @DefaultValue("100ms") Duration minThrottle,
            @DefaultValue("60s") Duration maxThrottle,
            @DefaultValue("30m") Duration timeout,
            @DefaultValue("1000") int maxSubscribers,
            @DefaultValue("true") boolean allowUnthrottled,
            @DefaultValue("*") List<String> allowedOrigins,
            @DefaultValue("30s") Duration heartbeat) {

        public Stream {
            if (heartbeat.isNegative()) {
                throw new IllegalArgumentException("mds.stream.heartbeat must not be negative, was " + heartbeat);
            }
        }
    }

    /**
     * Fixed for the life of the process; see {@link com.mds.math.Decimal}. The representable
     * range is {@code ±Long.MAX_VALUE / 10^scale}.
     *
     * @param scale    decimal places kept, 0 to 18
     * @param rounding rounding applied whenever a result has more places than {@code scale}
     */
    public record DecimalSettings(
            @DefaultValue("8") int scale,
            @DefaultValue("HALF_EVEN") RoundingMode rounding) {
    }

    /**
     * @param zone          time zone whose midnight starts each day/week/month/year candle
     * @param flushInterval how often in-progress candles are written and ended periods closed
     */
    public record Ohlc(
            @DefaultValue("UTC") ZoneId zone,
            @DefaultValue("10s") Duration flushInterval) {
    }

    /**
     * SOCKS5 proxy through which every exchange connection is made, both WebSocket streams and the
     * REST calls XChange makes while connecting. Disabled when {@code host} is blank. InfluxDB
     * traffic is not proxied. Proxy authentication is not supported.
     *
     * @param host proxy host name or IP; blank connects to exchanges directly
     * @param port proxy port
     */
    public record SocksProxy(String host, @DefaultValue("1080") int port) {

        public SocksProxy {
            host = host == null ? "" : host.trim();
            if (!host.isEmpty() && (port < 1 || port > 65535)) {
                throw new IllegalArgumentException("mds.socks-proxy.port must be between 1 and 65535, was " + port);
            }
        }

        public boolean enabled() {
            return !host.isEmpty();
        }

        @Override
        public String toString() {
            return enabled() ? host + ":" + port : "none";
        }
    }

    /**
     * The embedded Hazelcast cluster that every instance sharing an InfluxDB bucket must join. With no
     * {@code members} and no {@code kubernetesServiceDns} the instance is a cluster of one. Cluster
     * traffic is internal, like InfluxDB, and isn't proxied; it isn't encrypted or authenticated
     * either, so the port must only be reachable by other mds instances.
     *
     * @param name                 cluster name; instances join only a cluster with the same name
     * @param port                 first port to listen on; the next free one is used if it's taken
     * @param members              instances to join, {@code host} or {@code host:port}; without a port,
     *                             {@code port} and the next two are tried
     * @param kubernetesServiceDns headless Service whose DNS records list the instances, e.g.
     *                             {@code mds-hz.prod.svc.cluster.local}; blank when not on Kubernetes
     * @param rebalance            moving real-time subscribers off instances with more than their share
     */
    public record Cluster(
            @DefaultValue("mds") String name,
            @DefaultValue("5701") int port,
            @DefaultValue List<String> members,
            @DefaultValue("") String kubernetesServiceDns,
            @DefaultValue Rebalance rebalance) {

        public Cluster {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("mds.cluster.name must not be blank");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("mds.cluster.port must be between 1 and 65535, was " + port);
            }
            members = members == null ? List.of() : members.stream().map(String::trim).filter(m -> !m.isEmpty())
                    .toList();
            kubernetesServiceDns = kubernetesServiceDns == null ? "" : kubernetesServiceDns.trim();
            if (!members.isEmpty() && !kubernetesServiceDns.isEmpty()) {
                throw new IllegalArgumentException(
                        "set mds.cluster.members or mds.cluster.kubernetes-service-dns, not both");
            }
        }

        /** A cluster of one: nothing to join. */
        public boolean standalone() {
            return members.isEmpty() && kubernetesServiceDns.isEmpty();
        }
    }

    /**
     * Every {@code interval}, an instance whose subscriptions exceed the cluster's fair share
     * ({@code total / instances}) by more than {@code tolerance} closes its newest ones, down to the fair
     * share and at most {@code maxPerRound} at a time, so the clients reconnect through the load balancer
     * to an instance with fewer. SSE clients reconnect by themselves; WebSocket clients get close code
     * 1013 (try again later) and must reconnect.
     *
     * @param enabled     whether instances shed subscribers when they have more than their share
     * @param interval    how often each instance publishes its load and checks it
     * @param tolerance   how far above the fair share an instance may go before shedding, e.g. 0.2 = 20%
     * @param maxPerRound most subscriptions one instance closes per interval
     */
    public record Rebalance(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10s") Duration interval,
            @DefaultValue("0.2") double tolerance,
            @DefaultValue("20") int maxPerRound) {

        public Rebalance {
            if (tolerance < 0) {
                throw new IllegalArgumentException("mds.cluster.rebalance.tolerance must not be negative");
            }
            if (maxPerRound < 1) {
                throw new IllegalArgumentException("mds.cluster.rebalance.max-per-round must be at least 1");
            }
        }
    }
}
