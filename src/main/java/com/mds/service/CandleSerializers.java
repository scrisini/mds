package com.mds.service;

import java.time.Instant;
import java.util.List;

import com.hazelcast.nio.serialization.compact.CompactReader;
import com.hazelcast.nio.serialization.compact.CompactSerializer;
import com.hazelcast.nio.serialization.compact.CompactWriter;
import com.mds.math.Decimal;
import com.mds.model.Candle;
import com.mds.model.CandlePeriod;
import com.mds.model.MarketPair;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Compact (schema-based, no Java serialization) formats for the candle types the cluster stores and
 * sends between members. Prices are written as {@link Decimal} units, which is why every member must
 * use the same decimal scale ({@link com.mds.cluster.Cluster}). Changing a format changes its schema:
 * stop every instance before deploying one that does.
 */
@Configuration(proxyBeanMethods = false)
public class CandleSerializers {

    /** Every serializer, for tests that build a member without Spring. */
    public static List<CompactSerializer<?>> all() {
        return List.of(new CandleFormat(), new KeyFormat(), new CompletedKeyFormat(), new ApplyPriceFormat(),
                new RollForwardFormat());
    }

    @Bean
    CompactSerializer<Candle> candleFormat() {
        return new CandleFormat();
    }

    @Bean
    CompactSerializer<CandleKey> candleKeyFormat() {
        return new KeyFormat();
    }

    @Bean
    CompactSerializer<CompletedCandleKey> completedCandleKeyFormat() {
        return new CompletedKeyFormat();
    }

    @Bean
    CompactSerializer<ApplyPrice> applyPriceFormat() {
        return new ApplyPriceFormat();
    }

    @Bean
    CompactSerializer<RollForward> rollForwardFormat() {
        return new RollForwardFormat();
    }

    static final class CandleFormat implements CompactSerializer<Candle> {

        @Override
        public Candle read(CompactReader in) {
            return new Candle(in.readString("pair"), in.readString("source"),
                    CandlePeriod.parse(in.readString("period")), readInstant(in, "start_seconds", "start_nanos"),
                    readDecimal(in, "open"), readDecimal(in, "high"), readDecimal(in, "low"), readDecimal(in, "close"),
                    in.readInt64("updates"), in.readBoolean("complete"));
        }

        @Override
        public void write(CompactWriter out, Candle candle) {
            out.writeString("pair", candle.pair());
            out.writeString("source", candle.source());
            out.writeString("period", candle.period().id());
            writeInstant(out, "start_seconds", "start_nanos", candle.start());
            out.writeInt64("open", candle.open().units());
            out.writeInt64("high", candle.high().units());
            out.writeInt64("low", candle.low().units());
            out.writeInt64("close", candle.close().units());
            out.writeInt64("updates", candle.updates());
            out.writeBoolean("complete", candle.complete());
        }

        @Override
        public String getTypeName() {
            return "mds.Candle";
        }

        @Override
        public Class<Candle> getCompactClass() {
            return Candle.class;
        }
    }

    static final class KeyFormat implements CompactSerializer<CandleKey> {

        @Override
        public CandleKey read(CompactReader in) {
            return new CandleKey(in.readString("source"), MarketPair.parse(in.readString("pair")),
                    CandlePeriod.parse(in.readString("period")));
        }

        @Override
        public void write(CompactWriter out, CandleKey key) {
            out.writeString("source", key.source());
            out.writeString("pair", key.pair().toString());
            out.writeString("period", key.period().id());
        }

        @Override
        public String getTypeName() {
            return "mds.CandleKey";
        }

        @Override
        public Class<CandleKey> getCompactClass() {
            return CandleKey.class;
        }
    }

    static final class CompletedKeyFormat implements CompactSerializer<CompletedCandleKey> {

        @Override
        public CompletedCandleKey read(CompactReader in) {
            return new CompletedCandleKey(in.readCompact("series"), readInstant(in, "start_seconds", "start_nanos"));
        }

        @Override
        public void write(CompactWriter out, CompletedCandleKey key) {
            out.writeCompact("series", key.series());
            writeInstant(out, "start_seconds", "start_nanos", key.start());
        }

        @Override
        public String getTypeName() {
            return "mds.CompletedCandleKey";
        }

        @Override
        public Class<CompletedCandleKey> getCompactClass() {
            return CompletedCandleKey.class;
        }
    }

    static final class ApplyPriceFormat implements CompactSerializer<ApplyPrice> {

        @Override
        public ApplyPrice read(CompactReader in) {
            return new ApplyPrice(readDecimal(in, "price"), readInstant(in, "at_seconds", "at_nanos"),
                    in.readString("zone"));
        }

        @Override
        public void write(CompactWriter out, ApplyPrice update) {
            out.writeInt64("price", update.price().units());
            writeInstant(out, "at_seconds", "at_nanos", update.at());
            out.writeString("zone", update.zone());
        }

        @Override
        public String getTypeName() {
            return "mds.ApplyPrice";
        }

        @Override
        public Class<ApplyPrice> getCompactClass() {
            return ApplyPrice.class;
        }
    }

    static final class RollForwardFormat implements CompactSerializer<RollForward> {

        @Override
        public RollForward read(CompactReader in) {
            return new RollForward(readInstant(in, "now_seconds", "now_nanos"), in.readBoolean("active"),
                    in.readString("zone"));
        }

        @Override
        public void write(CompactWriter out, RollForward roll) {
            writeInstant(out, "now_seconds", "now_nanos", roll.now());
            out.writeBoolean("active", roll.active());
            out.writeString("zone", roll.zone());
        }

        @Override
        public String getTypeName() {
            return "mds.RollForward";
        }

        @Override
        public Class<RollForward> getCompactClass() {
            return RollForward.class;
        }
    }

    private static Decimal readDecimal(CompactReader in, String field) {
        return Decimal.ofUnits(in.readInt64(field));
    }

    // field names are literals at each call: these run for every candle update
    private static Instant readInstant(CompactReader in, String seconds, String nanos) {
        return Instant.ofEpochSecond(in.readInt64(seconds), in.readInt32(nanos));
    }

    private static void writeInstant(CompactWriter out, String seconds, String nanos, Instant instant) {
        out.writeInt64(seconds, instant.getEpochSecond());
        out.writeInt32(nanos, instant.getNano());
    }
}
