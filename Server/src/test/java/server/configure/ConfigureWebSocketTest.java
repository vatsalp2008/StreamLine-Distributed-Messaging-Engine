package server.configure;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import server.service.ChatService;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What the WebSocket endpoint is actually registered with.
 *
 * The handshake interceptor is what enforces access control, so a registration
 * that quietly dropped it would disable authentication on the chat endpoint
 * while every unit test of the interceptor itself kept passing.
 */
class ConfigureWebSocketTest {

    /** Records what the configurer registers, without a servlet container. */
    private static final class RecordingRegistry implements WebSocketHandlerRegistry {
        private final List<String> paths = new ArrayList<>();
        private final List<HandshakeInterceptor> interceptors = new ArrayList<>();
        private final List<String> origins = new ArrayList<>();
        private WebSocketHandler handler;

        @Override
        public WebSocketHandlerRegistration addHandler(WebSocketHandler webSocketHandler,
                String... registeredPaths) {
            this.handler = webSocketHandler;
            this.paths.addAll(List.of(registeredPaths));
            return new RecordingRegistration(this);
        }
    }

    /** Captures the fluent calls made after addHandler. */
    private record RecordingRegistration(RecordingRegistry registry)
            implements WebSocketHandlerRegistration {

        @Override
        public WebSocketHandlerRegistration addHandler(WebSocketHandler handler,
                String... paths) {
            return registry.addHandler(handler, paths);
        }

        @Override
        public WebSocketHandlerRegistration addInterceptors(
                HandshakeInterceptor... handshakeInterceptors) {
            registry.interceptors.addAll(List.of(handshakeInterceptors));
            return this;
        }

        @Override
        public WebSocketHandlerRegistration setAllowedOrigins(String... allowedOrigins) {
            registry.origins.addAll(List.of(allowedOrigins));
            return this;
        }

        @Override
        public WebSocketHandlerRegistration setAllowedOriginPatterns(String... patterns) {
            return this;
        }

        @Override
        public WebSocketHandlerRegistration setHandshakeHandler(
                org.springframework.web.socket.server.HandshakeHandler handshakeHandler) {
            return this;
        }

        @Override
        public org.springframework.web.socket.config.annotation.SockJsServiceRegistration
                withSockJS() {
            throw new UnsupportedOperationException("SockJS is not used");
        }
    }

    private RecordingRegistry register(StreamlineProperties properties) {
        ChatServerWSHandler handler = new ChatServerWSHandler(
                Validation.buildDefaultValidatorFactory().getValidator(),
                mock(ChatService.class), true);
        TokenHandshakeInterceptor interceptor =
                new TokenHandshakeInterceptor(new TokenAuthenticator(properties));

        RecordingRegistry registry = new RecordingRegistry();
        new ConfigureWebSocket(handler, properties, interceptor)
                .registerWebSocketHandlers(registry);
        return registry;
    }

    @Test
    void theChatEndpointIsRegisteredWithARoomPlaceholder() {
        assertThat(register(new StreamlineProperties()).paths).containsExactly("/chat/{roomId}");
    }

    @Test
    void theHandshakeInterceptorIsAlwaysRegistered() {
        RecordingRegistry registry = register(new StreamlineProperties());

        // without this, enabling auth would protect the API but not the socket
        assertThat(registry.interceptors)
                .hasAtLeastOneElementOfType(TokenHandshakeInterceptor.class);
    }

    @Test
    void theInterceptorIsRegisteredEvenWhenAuthIsOff() {
        StreamlineProperties properties = new StreamlineProperties();
        properties.getAuth().setEnabled(false);

        // it decides per-request; registration must not depend on current config
        assertThat(register(properties).interceptors)
                .hasAtLeastOneElementOfType(TokenHandshakeInterceptor.class);
    }

    @Test
    void configuredOriginsArePassedThrough() {
        StreamlineProperties properties = new StreamlineProperties();
        properties.getWs().setAllowedOrigins(new String[] {"https://example.test"});

        assertThat(register(properties).origins).containsExactly("https://example.test");
    }

    @Test
    void theDefaultAllowsAnyOrigin() {
        assertThat(register(new StreamlineProperties()).origins).containsExactly("*");
    }

    @Test
    void theRegisteredHandlerIsTheChatHandler() {
        assertThat(register(new StreamlineProperties()).handler)
                .isInstanceOf(ChatServerWSHandler.class);
    }
}
