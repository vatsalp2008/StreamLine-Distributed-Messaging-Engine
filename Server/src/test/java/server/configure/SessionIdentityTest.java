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
 * A session is bound to the username it joined with.
 *
 * Without this a single connection can attribute every message to a different
 * author, so neither a room's history nor its presence list means anything.
 */
class SessionIdentityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger IDS = new AtomicInteger();

    private Validator validator;
    private ChatService chatService;
    private ChatServerWSHandler handler;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        chatService = mock(ChatService.class);
        handler = handlerWith(new StreamlineProperties.Identity());
    }

    private ChatServerWSHandler handlerWith(StreamlineProperties.Identity identity) {
        return new ChatServerWSHandler(validator, chatService, true,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                new StreamlineProperties.RateLimit(),
                new ChatMetrics(new SimpleMeterRegistry()),
                identity);
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

    private JsonNode lastNonPresenceFrame(WebSocketSession session) throws IOException {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());

        List<JsonNode> frames = new ArrayList<>();
        for (WebSocketMessage<?> frame : captor.getAllValues()) {
            JsonNode node = MAPPER.readTree(frame.getPayload().toString());
            if (!"PRESENCE".equals(node.get("status").asText())) {
                frames.add(node);
            }
        }
        return frames.get(frames.size() - 1);
    }

    // ---------- impersonation ----------

    @Test
    void textClaimingAnotherUsernameIsRejected() throws IOException {
        WebSocketSession session = session("general");
        send(session, "alice", "JOIN");

        send(session, "bob", "TEXT");

        JsonNode response = lastNonPresenceFrame(session);
        assertThat(response.get("status").asText()).isEqualTo("ERROR");
        assertThat(response.get("message").asText())
                .contains("does not match the session")
                .contains("alice");
    }

    @Test
    void anImpersonatedMessageIsNotPersisted() throws IOException {
        WebSocketSession session = session("general");
        send(session, "alice", "JOIN");

        send(session, "bob", "TEXT");

        verify(chatService, never()).saveMessage(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void anImpersonatedMessageIsNotBroadcast() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession carol = session("general");
        send(alice, "alice", "JOIN");
        send(carol, "carol", "JOIN");

        send(alice, "bob", "TEXT");

        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(carol, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        for (WebSocketMessage<?> frame : captor.getAllValues()) {
            assertThat(frame.getPayload().toString()).doesNotContain("bob");
        }
    }

    @Test
    void leaveClaimingAnotherUsernameIsRejected() throws IOException {
        WebSocketSession session = session("general");
        send(session, "alice", "JOIN");

        send(session, "bob", "LEAVE");

        assertThat(lastNonPresenceFrame(session).get("status").asText()).isEqualTo("ERROR");
        // and the impostor did not remove alice from the room
        assertThat(handler.getRoomMembers("general")).containsExactly("alice");
    }

    @Test
    void theOwnUsernameIsStillAccepted() throws IOException {
        WebSocketSession session = session("general");
        send(session, "alice", "JOIN");

        send(session, "alice", "TEXT");

        assertThat(lastNonPresenceFrame(session).get("status").asText()).isEqualTo("OK");
    }

    @Test
    void usernameComparisonIsCaseSensitive() throws IOException {
        WebSocketSession session = session("general");
        send(session, "alice", "JOIN");

        send(session, "Alice", "TEXT");

        // "Alice" is a different member than "alice", so it must not pass
        assertThat(lastNonPresenceFrame(session).get("status").asText()).isEqualTo("ERROR");
    }

    // ---------- unique usernames ----------

    @Test
    void aUsernameAlreadyInTheRoomIsRefused() throws IOException {
        send(session("general"), "alice", "JOIN");

        WebSocketSession impostor = session("general");
        send(impostor, "alice", "JOIN");

        JsonNode response = lastNonPresenceFrame(impostor);
        assertThat(response.get("status").asText()).isEqualTo("ERROR");
        assertThat(response.get("message").asText()).contains("already in use");
    }

    @Test
    void theSameUsernameInADifferentRoomIsFine() throws IOException {
        send(session("one"), "alice", "JOIN");

        WebSocketSession other = session("two");
        send(other, "alice", "JOIN");

        assertThat(lastNonPresenceFrame(other).get("status").asText()).isEqualTo("OK");
    }

    @Test
    void aUsernameIsReusableOnceItsHolderLeaves() throws IOException {
        WebSocketSession alice = session("general");
        send(alice, "alice", "JOIN");
        send(alice, "alice", "LEAVE");

        WebSocketSession newcomer = session("general");
        send(newcomer, "alice", "JOIN");

        assertThat(lastNonPresenceFrame(newcomer).get("status").asText()).isEqualTo("OK");
    }

    // ---------- opting out ----------

    @Test
    void impersonationIsAllowedWhenStrictIdentityIsDisabled() throws IOException {
        StreamlineProperties.Identity permissive = new StreamlineProperties.Identity();
        permissive.setStrict(false);
        handler = handlerWith(permissive);

        WebSocketSession session = session("general");
        send(session, "alice", "JOIN");
        send(session, "bob", "TEXT");

        // load generators reuse one connection for many synthetic authors
        assertThat(lastNonPresenceFrame(session).get("status").asText()).isEqualTo("OK");
    }

    @Test
    void duplicateJoinsAreAllowedWhenUniquenessIsDisabled() throws IOException {
        StreamlineProperties.Identity permissive = new StreamlineProperties.Identity();
        permissive.setUniqueUsernames(false);
        handler = handlerWith(permissive);

        send(session("general"), "alice", "JOIN");
        WebSocketSession second = session("general");
        send(second, "alice", "JOIN");

        assertThat(lastNonPresenceFrame(second).get("status").asText()).isEqualTo("OK");
    }

    @Test
    void disablingStrictIdentityAlsoDisablesTheUniquenessCheck() throws IOException {
        StreamlineProperties.Identity permissive = new StreamlineProperties.Identity();
        permissive.setStrict(false);
        handler = handlerWith(permissive);

        send(session("general"), "alice", "JOIN");
        WebSocketSession second = session("general");
        send(second, "alice", "JOIN");

        assertThat(lastNonPresenceFrame(second).get("status").asText()).isEqualTo("OK");
    }
}
