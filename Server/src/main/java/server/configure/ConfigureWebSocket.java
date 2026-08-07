package server.configure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * class for WebSocket in Spring Boot
 */
@Configuration
@EnableWebSocket
public class ConfigureWebSocket implements WebSocketConfigurer {

    /**
     * WebSocket Handler to handle events and messages
     */
    private final ChatServerWSHandler handler;

    private final StreamlineProperties properties;

    /** Rejects unauthenticated handshakes before a session is created. */
    private final TokenHandshakeInterceptor tokenInterceptor;

    /**
     * Constructor - Spring will inject the handler and settings
     * @param handler    Represents the handler that manages WebSocket connections and msgs
     * @param properties Represents the streamline.* settings
     */
    public ConfigureWebSocket(ChatServerWSHandler handler, StreamlineProperties properties,
            TokenHandshakeInterceptor tokenInterceptor) {
        this.handler = handler;
        this.properties = properties;
        this.tokenInterceptor = tokenInterceptor;
    }

    /**
     * Registers WebSocket handlers with endpoints.
     * so we can say which urls maps to which handlers
     * @param -endpoints, Represents the WebSocketHandlerRegistry used to register endpoints handlers
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry endpoints) {
        endpoints.addHandler(handler, "/chat/{roomId}")
                .addInterceptors(tokenInterceptor)
                .setAllowedOrigins(properties.getWs().getAllowedOrigins());
    }

    /**
     * Caps what a single connection may consume.
     *
     * A chat message is at most 500 characters, so an 8 KB frame is already
     * generous; without a limit a client could stream an arbitrarily large frame.
     * The idle timeout reclaims sessions whose client vanished without closing,
     * which otherwise keep their room membership forever.
     *
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        StreamlineProperties.Ws ws = properties.getWs();

        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(ws.getMaxTextBytes());
        container.setMaxBinaryMessageBufferSize(ws.getMaxBinaryBytes());
        container.setMaxSessionIdleTimeout(ws.getIdleTimeoutMs());
        return container;
    }
}
