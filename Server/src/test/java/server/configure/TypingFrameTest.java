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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TYPING frames: transient hints that someone is composing a message.
 */
class TypingFrameTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger IDS = new AtomicInteger();

    private ChatService chatService;
    private ChatServerWSHandler handler;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        chatService = mock(ChatService.class);
        handler = new ChatServerWSHandler(validator, chatService, true,
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

    private void send(WebSocketSession session, String username, String type) throws IOException {
        handler.handleMessage(session, new TextMessage("""
                {"userId":7,"username":"%s","message":"hello there","timestamp":"2026-08-08T10:00:00Z","messageType":"%s"}
                """.formatted(username, type)));
    }

    private List<JsonNode> framesOfStatus(WebSocketSession session, String status)
            throws IOException {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeast(0)).sendMessage(captor.capture());

        List<JsonNode> frames = new ArrayList<>();
        for (WebSocketMessage<?> frame : captor.getAllValues()) {
            JsonNode node = MAPPER.readTree(frame.getPayload().toString());
            if (status.equals(node.get("status").asText())) {
                frames.add(node);
            }
        }
        return frames;
    }

    @Test
    void typingReachesTheOtherMembersOfTheRoom() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession bob = session("general");
        send(alice, "alice", "JOIN");
        send(bob, "bob", "JOIN");

        send(alice, "alice", "TYPING");

        List<JsonNode> seen = framesOfStatus(bob, "TYPING");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).get("message").asText()).isEqualTo("alice");
    }

    @Test
    void theTypistDoesNotSeeTheirOwnIndicator() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession bob = session("general");
        send(alice, "alice", "JOIN");
        send(bob, "bob", "JOIN");

        send(alice, "alice", "TYPING");

        assertThat(framesOfStatus(alice, "TYPING")).isEmpty();
    }

    @Test
    void typingIsNeverPersisted() throws IOException {
        WebSocketSession alice = session("general");
        send(alice, "alice", "JOIN");

        send(alice, "alice", "TYPING");

        // it is a hint, not chat history
        verify(chatService, never()).saveMessage(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void typingIsNotAcknowledgedAsAMessage() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession bob = session("general");
        send(alice, "alice", "JOIN");
        send(bob, "bob", "JOIN");
        int acksBefore = framesOfStatus(alice, "OK").size();

        send(alice, "alice", "TYPING");

        assertThat(framesOfStatus(alice, "OK")).hasSize(acksBefore);
    }

    @Test
    void typingIsNotBroadcastAsChat() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession bob = session("general");
        send(alice, "alice", "JOIN");
        send(bob, "bob", "JOIN");
        int chatBefore = framesOfStatus(bob, "BROADCAST").size();

        send(alice, "alice", "TYPING");

        assertThat(framesOfStatus(bob, "BROADCAST")).hasSize(chatBefore);
    }

    @Test
    void typingBeforeJoinIsRejected() throws IOException {
        WebSocketSession alice = session("general");

        send(alice, "alice", "TYPING");

        List<JsonNode> errors = framesOfStatus(alice, "ERROR");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("message").asText())
                .isEqualTo("You must JOIN before sending TYPING");
    }

    @Test
    void typingDoesNotCrossRooms() throws IOException {
        WebSocketSession alice = session("one");
        WebSocketSession bob = session("two");
        send(alice, "alice", "JOIN");
        send(bob, "bob", "JOIN");

        send(alice, "alice", "TYPING");

        assertThat(framesOfStatus(bob, "TYPING")).isEmpty();
    }

    @Test
    void typingUnderAnotherUsernameIsRejected() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession bob = session("general");
        send(alice, "alice", "JOIN");
        send(bob, "bob", "JOIN");

        send(alice, "bob", "TYPING");

        // identity rules apply to hints as well as to chat
        assertThat(framesOfStatus(alice, "ERROR")).hasSize(1);
        assertThat(framesOfStatus(bob, "TYPING")).isEmpty();
    }

    @Test
    void repeatedTypingKeepsNotifyingTheRoom() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession bob = session("general");
        send(alice, "alice", "JOIN");
        send(bob, "bob", "JOIN");

        send(alice, "alice", "TYPING");
        send(alice, "alice", "TYPING");

        // the client decides how often to send; the server does not dedupe
        assertThat(framesOfStatus(bob, "TYPING")).hasSize(2);
    }
}
