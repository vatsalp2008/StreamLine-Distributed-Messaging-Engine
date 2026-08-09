package server.configure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import server.service.ChatService;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The optional clientId a sender attaches so it can tell which of its messages
 * a reply answers.
 *
 * Without it a client can only assume the next acknowledgement is its own, which
 * stops being true as soon as anything else arrives on the same connection.
 */
class CorrelationIdTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger IDS = new AtomicInteger();

    private ChatServerWSHandler handler;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        handler = new ChatServerWSHandler(validator, mock(ChatService.class), true,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                new StreamlineProperties.RateLimit(),
                new ChatMetrics(new SimpleMeterRegistry()),
                new StreamlineProperties.Identity());
    }

    private WebSocketSession session(String room) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("s" + IDS.incrementAndGet());
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/chat/" + room));
        return session;
    }

    /** Sends a frame, optionally carrying a correlation id. */
    private void send(WebSocketSession session, String username, String type, String clientId)
            throws IOException {
        String correlation = clientId == null ? "" : ",\"clientId\":\"%s\"".formatted(clientId);
        handler.handleMessage(session, new TextMessage("""
                {"userId":7,"username":"%s","message":"hello there","timestamp":"2026-08-09T10:00:00Z","messageType":"%s"%s}
                """.formatted(username, type, correlation)));
    }

    private List<JsonNode> framesTo(WebSocketSession session) throws IOException {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());

        List<JsonNode> frames = new ArrayList<>();
        for (WebSocketMessage<?> frame : captor.getAllValues()) {
            frames.add(MAPPER.readTree(frame.getPayload().toString()));
        }
        return frames;
    }

    private JsonNode lastDirectReply(WebSocketSession session) throws IOException {
        List<JsonNode> replies = framesTo(session).stream()
                .filter(f -> {
                    String status = f.get("status").asText();
                    return "OK".equals(status) || "ERROR".equals(status);
                })
                .toList();
        return replies.get(replies.size() - 1);
    }

    @Test
    void anAcknowledgementCarriesTheIdItAnswers() throws IOException {
        WebSocketSession session = session("general");
        send(session, "alice", "JOIN", "join-1");

        assertThat(lastDirectReply(session).get("clientId").asText()).isEqualTo("join-1");
    }

    @Test
    void eachReplyCarriesItsOwnId() throws IOException {
        WebSocketSession session = session("general");
        send(session, "alice", "JOIN", "m1");
        send(session, "alice", "TEXT", "m2");
        send(session, "alice", "TEXT", "m3");

        List<String> ids = framesTo(session).stream()
                .filter(f -> f.has("clientId"))
                .map(f -> f.get("clientId").asText())
                .toList();

        assertThat(ids).containsExactly("m1", "m2", "m3");
    }

    @Test
    void aRefusalAlsoCarriesTheId() throws IOException {
        WebSocketSession session = session("general");

        // TEXT before JOIN is refused; the sender still needs to know which
        // message was refused
        send(session, "alice", "TEXT", "doomed-1");

        JsonNode reply = lastDirectReply(session);
        assertThat(reply.get("status").asText()).isEqualTo("ERROR");
        assertThat(reply.get("clientId").asText()).isEqualTo("doomed-1");
    }

    @Test
    void aValidationFailureCarriesTheId() throws IOException {
        WebSocketSession session = session("general");

        send(session, "x", "JOIN", "bad-1"); // username too short

        JsonNode reply = lastDirectReply(session);
        assertThat(reply.get("message").asText()).startsWith("Validation failed");
        assertThat(reply.get("clientId").asText()).isEqualTo("bad-1");
    }

    @Test
    void anIdentityRefusalCarriesTheId() throws IOException {
        WebSocketSession session = session("general");
        send(session, "alice", "JOIN", "j");

        send(session, "bob", "TEXT", "impostor-1");

        assertThat(lastDirectReply(session).get("clientId").asText()).isEqualTo("impostor-1");
    }

    @Test
    void omittingTheIdLeavesItOutOfTheReply() throws IOException {
        WebSocketSession session = session("general");

        send(session, "alice", "JOIN", null);

        // the field is optional; a client that does not use it sees no change
        assertThat(lastDirectReply(session).has("clientId")).isFalse();
    }

    @Test
    void anEmptyIdIsTreatedAsAbsent() throws IOException {
        WebSocketSession session = session("general");

        send(session, "alice", "JOIN", "");

        assertThat(lastDirectReply(session).has("clientId")).isFalse();
    }

    @Test
    void unsolicitedPushesCarryNoId() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession bob = session("general");
        send(alice, "alice", "JOIN", "a1");
        send(bob, "bob", "JOIN", "b1");

        send(alice, "alice", "TEXT", "a2");

        // bob's BROADCAST and PRESENCE frames answer nothing he sent, so
        // attaching alice's id to them would be actively misleading
        for (JsonNode frame : framesTo(bob)) {
            String status = frame.get("status").asText();
            if (!"OK".equals(status) && !"ERROR".equals(status)) {
                assertThat(frame.has("clientId"))
                        .as("%s frame should carry no clientId", status)
                        .isFalse();
            }
        }
    }

    @Test
    void theIdIsNotStoredAsPartOfTheMessage() throws IOException {
        ChatService chatService = mock(ChatService.class);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        handler = new ChatServerWSHandler(validator, chatService, true,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                new StreamlineProperties.RateLimit(),
                new ChatMetrics(new SimpleMeterRegistry()),
                new StreamlineProperties.Identity());

        WebSocketSession session = session("general");
        send(session, "alice", "JOIN", "j1");
        send(session, "alice", "TEXT", "t1");

        ArgumentCaptor<server.model.ChatMessage> saved =
                ArgumentCaptor.forClass(server.model.ChatMessage.class);
        verify(chatService).saveMessage(saved.capture(), org.mockito.ArgumentMatchers.anyString());

        // it is a transport concern for one exchange, not part of the history
        assertThat(saved.getValue().getMessage()).isEqualTo("hello there");
        assertThat(saved.getValue().getClientId()).isEqualTo("t1");
    }

    @Test
    void anOverlongIdIsRejectedByValidation() throws IOException {
        WebSocketSession session = session("general");

        send(session, "alice", "JOIN", "x".repeat(65));

        assertThat(lastDirectReply(session).get("message").asText())
                .startsWith("Validation failed");
    }
}
