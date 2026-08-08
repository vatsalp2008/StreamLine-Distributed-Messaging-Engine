package server.configure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import server.service.ChatService;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricsConfigTest {

    private ChatServerWSHandler handler;
    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        handler = new ChatServerWSHandler(validator, mock(ChatService.class), true);
        registry = new SimpleMeterRegistry();
        new MetricsConfig(registry, handler);
    }

    private void join(String room, String username) throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/chat/" + room));

        handler.handleMessage(session, new TextMessage("""
                {"userId":1,"username":"%s","message":"Joining","timestamp":"2026-08-05T10:00:00Z","messageType":"JOIN"}
                """.formatted(username)));
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    void gaugesAreRegisteredAndStartAtZero() {
        assertThat(gauge("streamline.rooms.active")).isZero();
        assertThat(gauge("streamline.sessions.joined")).isZero();
    }

    @Test
    void gaugesTrackLiveRoomStateRatherThanASnapshot() throws IOException {
        join("1", "alice");
        join("1", "bob");
        join("2", "carol");

        // gauges read through to the handler, so later joins are reflected
        assertThat(gauge("streamline.rooms.active")).isEqualTo(2.0);
        assertThat(gauge("streamline.sessions.joined")).isEqualTo(3.0);
    }

    @Test
    void gaugesCarryADescription() {
        assertThat(registry.get("streamline.rooms.active").gauge().getId().getDescription())
                .isNotBlank();
        assertThat(registry.get("streamline.sessions.joined").gauge().getId().getDescription())
                .isNotBlank();
    }

    @Test
    void identityRejectionsAreCountedSeparatelyAndInTheTotal() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatMetrics metrics = new ChatMetrics(registry);

        metrics.recordIdentityRejected();
        metrics.recordRejected();

        // the specific counter isolates probing from ordinary validation errors
        assertThat(registry.counter("streamline.messages.identity_rejected").count())
                .isEqualTo(1.0);
        // and both still roll up into the overall rejection count
        assertThat(registry.counter("streamline.messages.rejected").count()).isEqualTo(2.0);
    }

    @Test
    void typingHintsAreCountedByRecipient() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatMetrics metrics = new ChatMetrics(registry);

        metrics.recordTyping(3);
        metrics.recordTyping(0);

        // a hint delivered to nobody still costs nothing and adds nothing
        assertThat(registry.counter("streamline.typing.sent").count()).isEqualTo(3.0);
    }
}
