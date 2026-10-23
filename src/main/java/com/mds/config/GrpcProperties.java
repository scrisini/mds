package com.mds.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The gRPC API ({@code mds.v1.MarketData}). The server itself (port, keep-alive, shutdown grace) is
 * configured under {@code spring.grpc.server.*}.
 *
 * @param compression how responses are compressed when the client accepts it. Each message is
 *                    compressed on its own: measured, gzip saves 13% on an aggregate with quotes
 *                    (420 bytes) and adds 8% to one without (111 bytes). {@code auto} compresses only
 *                    responses with quotes; {@code gzip} all; {@code none} none (README "gRPC API")
 */
@ConfigurationProperties("mds.grpc")
public record GrpcProperties(@DefaultValue("auto") Compression compression) {

    public enum Compression {
        AUTO, GZIP, NONE;

        /** Whether a response is worth compressing. */
        public boolean applies(boolean includesQuotes) {
            return this == GZIP || (this == AUTO && includesQuotes);
        }
    }
}
