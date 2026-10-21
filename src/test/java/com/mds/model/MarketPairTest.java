package com.mds.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MarketPairTest {

    @ParameterizedTest
    @ValueSource(strings = {"BTC-USDT", "btc/usdt", " BTC_USDT "})
    void parsesSupportedSeparators(String value) {
        MarketPair pair = MarketPair.parse(value);

        assertThat(pair.base()).isEqualTo("BTC");
        assertThat(pair.counter()).isEqualTo("USDT");
        assertThat(pair).hasToString("BTC/USDT");
        assertThat(pair.symbol()).isEqualTo("BTC-USDT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "BTC-USDT-X", "BTC-\"USD", "B-USD", ""})
    void rejectsMalformedPairs(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> MarketPair.parse(value));
    }
}
