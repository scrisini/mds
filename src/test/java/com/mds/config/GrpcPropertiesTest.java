package com.mds.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GrpcPropertiesTest {

    @Test
    void autoCompressesOnlyResponsesWithQuotes() {
        assertThat(GrpcProperties.Compression.AUTO.applies(true)).isTrue();
        assertThat(GrpcProperties.Compression.AUTO.applies(false)).isFalse();
        assertThat(GrpcProperties.Compression.GZIP.applies(false)).isTrue();
        assertThat(GrpcProperties.Compression.NONE.applies(true)).isFalse();
    }
}
