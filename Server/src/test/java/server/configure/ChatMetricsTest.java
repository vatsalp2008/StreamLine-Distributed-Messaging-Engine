package server.configure;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

class ChatMetricsTest {

    private MeterRegistry registry;
    private ChatMetrics metrics;
    private ChatServerWSHandler handler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ChatMetrics(registry);

        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        handler = new ChatServerWSHandler(validator, mock(ChatService.class), true,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                new StreamlineProperties.RateLimit(), metrics);
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    private WebSocketSession session(String room) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/chat/" + room));
        return session;
    }

    private void send(WebSocketSession session, String username, String text, String type)
            throws IOException {
        handler.handleMessage(session, new TextMessage("""
                {"userId":7,"username":"%s","message":"%s","timestamp":"2026-08-06T10:00:00Z","messageType":"%s"}
                """.formatted(username, text, type)));
    }

    @Test
    void countersStartAtZero() {
        assertThat(count("streamline.messages.accepted")).isZero();
        assertThat(count("streamline.messages.rejected")).isZero();
        assertThat(count("streamline.messages.rate_limited")).isZero();
        assertThat(count("streamline.broadcasts.sent")).isZero();
    }

    @Test
    void validTrafficIsCountedAsAccepted() throws IOException {
        WebSocketSession alice = session("general");

        send(alice, "alice", "Joining", "JOIN");
        send(alice, "alice", "hello", "TEXT");

        assertThat(count("streamline.messages.accepted")).isEqualTo(2);
        assertThat(count("streamline.messages.rejected")).isZero();
    }

    @Test
    void validationFailuresAreCountedAsRejected() throws IOException {
        send(session("general"), "x", "hello", "TEXT");

        assertThat(count("streamline.messages.rejected")).isEqualTo(1);
        assertThat(count("streamline.messages.accepted")).isZero();
    }

    @Test
    void protocolViolationsAreCountedAsRejected() throws IOException {
        // TEXT before JOIN is well-formed but not allowed
        send(session("general"), "alice", "hello", "TEXT");

        assertThat(count("streamline.messages.rejected")).isEqualTo(1);
    }

    @Test
    void unparseableFramesAreCountedAsRejected() throws IOException {
        handler.handleMessage(session("general"), new TextMessage("not json at all"));

        assertThat(count("streamline.messages.rejected")).isEqualTo(1);
    }

    @Test
    void broadcastCountsEachRecipientRatherThanEachMessage() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession bob = session("general");
        WebSocketSession carol = session("general");

        send(alice, "alice", "Joining", "JOIN");
        send(bob, "bob", "Joining", "JOIN");
        send(carol, "carol", "Joining", "JOIN");

        double afterJoins = count("streamline.broadcasts.sent");

        send(alice, "alice", "hello room", "TEXT");

        // one message reaching two peers is two delivered copies
        assertThat(count("streamline.broadcasts.sent") - afterJoins).isEqualTo(2);
    }

    @Test
    void aMessageWithNoPeersRecordsNoBroadcast() throws IOException {
        WebSocketSession alice = session("empty-room");

        send(alice, "alice", "Joining", "JOIN");
        send(alice, "alice", "talking to myself", "TEXT");

        assertThat(count("streamline.broadcasts.sent")).isZero();
    }

    @Test
    void rateLimitedFramesAreCountedSeparately() throws IOException {
        StreamlineProperties.RateLimit limits = new StreamlineProperties.RateLimit();
        limits.setEnabled(true);
        limits.setMessagesPerSecond(1);
        limits.setBurstSize(1);

        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        handler = new ChatServerWSHandler(validator, mock(ChatService.class), true,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(), limits, metrics);

        WebSocketSession alice = session("general");
        when(alice.getId()).thenReturn("session-1");

        send(alice, "alice", "Joining", "JOIN");
        send(alice, "alice", "too fast", "TEXT");
        send(alice, "alice", "too fast", "TEXT");

        assertThat(count("streamline.messages.rate_limited")).isGreaterThan(0);
    }

    @Test
    void everyCounterIsRegisteredWithADescription() {
        for (String name : new String[]{
                "streamline.messages.accepted",
                "streamline.messages.rejected",
                "streamline.messages.rate_limited",
                "streamline.broadcasts.sent"}) {
            assertThat(registry.get(name).counter().getId().getDescription())
                    .as("description for %s", name)
                    .isNotBlank();
        }
    }
}
