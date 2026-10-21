package com.mds.exchange;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mds.config.MdsProperties;
import com.mds.model.MarketPair;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.instrument.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds one initialised XChange {@link Exchange} per configured exchange id.
 * Exchanges that fail to initialise (unknown id, network error, geo-block) are
 * logged and left out rather than failing application startup.
 */
@Component
public class ExchangeRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRegistry.class);

    private final Map<String, Exchange> exchanges;

    public ExchangeRegistry(MdsProperties properties) {
        Map<String, Exchange> connected = new LinkedHashMap<>();
        for (String id : properties.exchanges()) {
            SupportedExchange.fromId(id).ifPresentOrElse(
                    supported -> connect(supported, properties.fetchTimeout())
                            .ifPresent(exchange -> connected.put(supported.id(), exchange)),
                    () -> log.warn("Ignoring unsupported exchange '{}'", id));
        }
        this.exchanges = Collections.unmodifiableMap(connected);
        log.info("Connected exchanges: {}", exchanges.keySet());
    }

    public Set<String> ids() {
        return exchanges.keySet();
    }

    public Optional<Exchange> find(String id) {
        return Optional.ofNullable(exchanges.get(id.toLowerCase()));
    }

    /**
     * Ids of exchanges that list the pair. Exchanges whose instrument metadata could
     * not be loaded are assumed to support it and will report an error on fetch.
     */
    public List<String> supporting(MarketPair pair) {
        Instrument instrument = pair.toCurrencyPair();
        return exchanges.entrySet().stream()
                .filter(e -> {
                    List<Instrument> listed = e.getValue().getExchangeInstruments();
                    return listed == null || listed.isEmpty() || listed.contains(instrument);
                })
                .map(Map.Entry::getKey)
                .toList();
    }

    private static Optional<Exchange> connect(SupportedExchange supported, Duration timeout) {
        try {
            Exchange exchange = ExchangeFactory.INSTANCE.createExchangeWithoutSpecification(supported.exchangeClass());
            ExchangeSpecification spec = exchange.getDefaultExchangeSpecification();
            spec.setHttpConnTimeout((int) timeout.toMillis());
            spec.setHttpReadTimeout((int) timeout.toMillis());
            exchange.applySpecification(spec);
            return Optional.of(exchange);
        } catch (RuntimeException e) {
            log.warn("Could not initialise exchange '{}': {}", supported.id(), e.getMessage());
            return Optional.empty();
        }
    }
}
