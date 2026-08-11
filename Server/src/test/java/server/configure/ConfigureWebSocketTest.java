package server.configure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the chat endpoint is registered.
 *
 * Worth asserting because the wiring is easy to break silently: dropping the
 * handshake interceptor would leave every room open with no test failing, and
 * the buffer and idle limits only exist as registration-time settings.
 */
@SpringBootTest(
        // a real servlet container: the container settings bean resolves the
        // ServerContainer from the servlet context, which the mock environment
        // does not provide
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        "spring.datasource.url=jdbc:h2:mem:streamline-wsconfig;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "streamline.ws.max-text-bytes=4096",
        "streamline.ws.idle-timeout-ms=60000"
        })
class ConfigureWebSocketTest {

    @Autowired
    private ConfigureWebSocket configuration;

    @Autowired
    private TokenHandshakeInterceptor interceptor;

    @Autowired
    private ServletServerContainerFactoryBean containerSettings;

    @Autowired
    private StreamlineProperties properties;

    @Test
    void theChatEndpointIsRegisteredWithTheInterceptor() {
        RecordingRegistry registry = new RecordingRegistry();

        configuration.registerWebSocketHandlers(registry);

        assertThat(registry.paths).containsExactly("/chat/{roomId}");
        // without this every room would be reachable without a token
        assertThat(registry.interceptors).contains(interceptor);
    }

    @Test
    void allowedOriginsComeFromConfiguration() {
        RecordingRegistry registry = new RecordingRegistry();

        configuration.registerWebSocketHandlers(registry);

        assertThat(registry.origins).containsExactly(properties.getWs().getAllowedOrigins());
    }

    @Test
    void bufferLimitsAreAppliedToTheContainer() {
        // a frame larger than this is rejected rather than buffered whole
        assertThat(containerSettings.getMaxTextMessageBufferSize()).isEqualTo(4096);
    }

    @Test
    void theIdleTimeoutIsAppliedToTheContainer() {
        // reclaims sessions whose client vanished without closing, which would
        // otherwise hold their room membership forever
        assertThat(containerSettings.getMaxSessionIdleTimeout()).isEqualTo(60000L);
    }

    /** Captures what the configuration registers, in place of the real registry. */
    private static final class RecordingRegistry
            implements org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry {

        private final java.util.List<String> paths = new java.util.ArrayList<>();
        private final java.util.List<Object> interceptors = new java.util.ArrayList<>();
        private String[] origins = new String[0];

        @Override
        public org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration
                addHandler(org.springframework.web.socket.WebSocketHandler handler,
                        String... pathPatterns) {
            paths.addAll(java.util.List.of(pathPatterns));
            return new RecordingRegistration(this);
        }
    }

    /** Records the settings applied to one registration. */
    private static final class RecordingRegistration
            implements org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration {

        private final RecordingRegistry registry;

        private RecordingRegistration(RecordingRegistry registry) {
            this.registry = registry;
        }

        @Override
        public org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration
                addHandler(org.springframework.web.socket.WebSocketHandler handler,
                        String... pathPatterns) {
            return this;
        }

        @Override
        public org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration
                setHandshakeHandler(
                        org.springframework.web.socket.server.HandshakeHandler handshakeHandler) {
            return this;
        }

        @Override
        public org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration
                addInterceptors(
                        org.springframework.web.socket.server.HandshakeInterceptor... added) {
            registry.interceptors.addAll(java.util.List.of(added));
            return this;
        }

        @Override
        public org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration
                setAllowedOrigins(String... allowedOrigins) {
            registry.origins = allowedOrigins;
            return this;
        }

        @Override
        public org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration
                setAllowedOriginPatterns(String... allowedOriginPatterns) {
            return this;
        }

        @Override
        public org.springframework.web.socket.config.annotation.SockJsServiceRegistration
                withSockJS() {
            throw new UnsupportedOperationException("SockJS is not used");
        }
    }
}
