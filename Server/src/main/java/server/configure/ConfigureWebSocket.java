package server.configure;

import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Constructor - Spring will inject the handler
     * @param -chatHandler, Represents the handler that manages WebSocket connections and msgs
     */
    public ConfigureWebSocket(ChatServerWSHandler handler) {
        this.handler = handler;
    }

    /**
     * Registers WebSocket handlers with endpoints.
     * so we can say which urls maps to which handlers
     * @param -endpoints, Represents the WebSocketHandlerRegistry used to register endpoints handlers
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry endpoints) {
        endpoints.addHandler(handler, "/chat/{roomId}")
                .setAllowedOrigins("*");
    }

    /**
     * Caps what a single connection may consume.
     *
     * A chat message is at most 500 characters, so an 8 KB frame is already
     * generous; without a limit a client could stream an arbitrarily large frame.
     * The idle timeout reclaims sessions whose client vanished without closing,
     * which otherwise keep their room membership forever.
     *
     * @param maxTextBytes   -int, largest accepted text frame
     * @param maxBinaryBytes -int, largest accepted binary frame
     * @param idleTimeoutMs  -long, how long a silent session is kept open
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer(
            @Value("${streamline.ws.max-text-bytes:8192}") int maxTextBytes,
            @Value("${streamline.ws.max-binary-bytes:8192}") int maxBinaryBytes,
            @Value("${streamline.ws.idle-timeout-ms:300000}") long idleTimeoutMs) {

        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(maxTextBytes);
        container.setMaxBinaryMessageBufferSize(maxBinaryBytes);
        container.setMaxSessionIdleTimeout(idleTimeoutMs);
        return container;
    }
}
