package server.configure;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

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
}
