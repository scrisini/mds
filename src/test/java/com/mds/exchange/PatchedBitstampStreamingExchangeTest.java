package com.mds.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import info.bitrich.xchangestream.bitstamp.v2.BitstampStreamingService;
import org.junit.jupiter.api.Test;

class PatchedBitstampStreamingExchangeTest {

    @Test
    void upstreamServiceKeysSubscriptionsDifferentlyFromIncomingChannelNames() {
        // Guards the workaround: if this starts failing, upstream is fixed and the patch can go.
        assertThat(new BitstampStreamingService("wss://example.invalid")
                .getSubscriptionUniqueId("order_book_btcusd", "data"))
                .isNotEqualTo("order_book_btcusd");
    }
}
