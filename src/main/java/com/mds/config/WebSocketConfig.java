package com.mds.config;

import com.mds.web.PriceWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** Registers the real-time price WebSocket at {@code /api/v1/ws/prices}. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    public static final String PRICES_PATH = "/api/v1/ws/prices";

    /** Client messages are small subscribe/unsubscribe commands. */
    private static final int MAX_CLIENT_MESSAGE_BYTES = 8 * 1024;

    private final PriceWebSocketHandler handler;
    private final MdsProperties.Stream settings;

    public WebSocketConfig(PriceWebSocketHandler handler, MdsProperties properties) {
        this.handler = handler;
        this.settings = properties.stream();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, PRICES_PATH)
                .setAllowedOriginPatterns(settings.allowedOrigins().toArray(String[]::new));
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_CLIENT_MESSAGE_BYTES);
        container.setMaxSessionIdleTimeout(settings.timeout().toMillis());
        return container;
    }
}
