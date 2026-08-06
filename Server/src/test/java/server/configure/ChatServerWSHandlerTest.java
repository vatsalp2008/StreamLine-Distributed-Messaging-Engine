package server.configure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import server.model.ChatMessage;
import server.service.ChatService;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServerWSHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Validator validator;

    private ChatService chatService;
    private ChatServerWSHandler handler;

    @BeforeAll
    static void initValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        handler = new ChatServerWSHandler(validator, chatService, true);
    }

    // ---------- helpers ----------

    private WebSocketSession session(String path) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        if (path != null) {
            when(session.getUri()).thenReturn(URI.create("ws://localhost:8080" + path));
        }
        return session;
    }

    private String payload(String username, String message, String type) {
        return """
                {"userId":7,"username":"%s","message":"%s","timestamp":"2026-08-04T10:00:00Z","messageType":"%s"}
                """.formatted(username, message, type);
    }

    /** All frames written to the session, in order, as parsed JSON. */
    private List<JsonNode> sentFrames(WebSocketSession session) throws IOException {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());

        List<JsonNode> frames = new ArrayList<>();
        for (WebSocketMessage<?> frame : captor.getAllValues()) {
            frames.add(MAPPER.readTree(frame.getPayload().toString()));
        }
        return frames;
    }

    private JsonNode lastFrame(WebSocketSession session) throws IOException {
        List<JsonNode> frames = sentFrames(session);
        return frames.get(frames.size() - 1);
    }

    private void send(WebSocketSession session, String json) throws IOException {
        handler.handleMessage(session, new TextMessage(json));
    }

    // ---------- join / leave / text state machine ----------

    @Test
    void joinAcknowledgesAndAnnouncesTheUser() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, payload("alice", "Joining", "JOIN"));

        JsonNode response = lastFrame(session);
        assertThat(response.get("status").asText()).isEqualTo("OK");
        assertThat(response.get("message").asText()).isEqualTo("alice joined the room");
        assertThat(response.hasNonNull("serverTimestamp")).isTrue();
    }

    @Test
    void secondJoinOnSameSessionIsRejected() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, payload("alice", "Joining", "JOIN"));
        send(session, payload("alice", "Joining", "JOIN"));

        JsonNode response = lastFrame(session);
        assertThat(response.get("status").asText()).isEqualTo("ERROR");
        assertThat(response.get("message").asText()).isEqualTo("Already joined");
    }

    @Test
    void textBeforeJoinIsRejected() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, payload("alice", "hello", "TEXT"));

        JsonNode response = lastFrame(session);
        assertThat(response.get("status").asText()).isEqualTo("ERROR");
        assertThat(response.get("message").asText()).isEqualTo("You must JOIN before sending TEXT");
    }

    @Test
    void textAfterJoinIsEchoedBackToSender() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, payload("alice", "Joining", "JOIN"));
        send(session, payload("alice", "hello world", "TEXT"));

        JsonNode response = lastFrame(session);
        assertThat(response.get("status").asText()).isEqualTo("OK");
        assertThat(response.get("message").asText()).isEqualTo("alice: hello world");
    }

    @Test
    void leaveBeforeJoinIsRejected() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, payload("alice", "Leaving", "LEAVE"));

        JsonNode response = lastFrame(session);
        assertThat(response.get("status").asText()).isEqualTo("ERROR");
        assertThat(response.get("message").asText()).isEqualTo("You must JOIN before LEAVE");
    }

    @Test
    void leaveAfterJoinRemovesTheSessionFromTheRoom() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, payload("alice", "Joining", "JOIN"));
        send(session, payload("alice", "Leaving", "LEAVE"));

        assertThat(lastFrame(session).get("message").asText()).isEqualTo("alice left the room");

        // having left, the session is back to the pre-join state
        send(session, payload("alice", "hello", "TEXT"));
        assertThat(lastFrame(session).get("status").asText()).isEqualTo("ERROR");
    }

    // ---------- validation ----------

    @Test
    void usernameFailingThePatternIsRejected() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, payload("bad user!", "hello", "JOIN"));

        JsonNode response = lastFrame(session);
        assertThat(response.get("status").asText()).isEqualTo("ERROR");
        assertThat(response.get("message").asText()).startsWith("Validation failed");
    }

    @Test
    void unknownMessageTypeIsRejected() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, payload("alice", "hello", "SHOUT"));

        assertThat(lastFrame(session).get("status").asText()).isEqualTo("ERROR");
    }

    @Test
    void malformedJsonIsReportedInsteadOfClosingTheConnection() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, "this is not json");

        JsonNode response = lastFrame(session);
        assertThat(response.get("status").asText()).isEqualTo("ERROR");
        assertThat(response.get("message").asText()).startsWith("Error parsing message");
    }

    @Test
    void textMessagesArePersisted() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, payload("alice", "Joining", "JOIN"));
        send(session, payload("alice", "hello", "TEXT"));

        verify(chatService).saveMessage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("5"));
    }

    @Test
    void joinAndLeaveControlFramesAreNotPersisted() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, payload("alice", "Joining", "JOIN"));
        send(session, payload("alice", "Leaving", "LEAVE"));

        // control frames are connection state, not chat history
        verify(chatService, never()).saveMessage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void invalidMessagesAreNotPersisted() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, payload("x", "hello", "TEXT"));

        verify(chatService, never()).saveMessage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    // ---------- history replay ----------

    @Test
    void joinReplaysRoomHistoryOldestFirst() throws IOException {
        WebSocketSession session = session("/chat/9");
        when(chatService.getRecentMessages("9")).thenReturn(List.of(
                history("bob", "newest"),
                history("carol", "oldest")));

        send(session, payload("alice", "Joining", "JOIN"));

        List<JsonNode> frames = sentFrames(session);
        assertThat(frames).hasSize(3);
        assertThat(frames.get(0).get("status").asText()).isEqualTo("HISTORY");
        assertThat(frames.get(0).get("message").asText()).isEqualTo("carol: oldest");
        assertThat(frames.get(1).get("message").asText()).isEqualTo("bob: newest");
        assertThat(frames.get(2).get("status").asText()).isEqualTo("OK");
    }

    private ChatMessage history(String username, String text) {
        ChatMessage msg = new ChatMessage();
        msg.setUsername(username);
        msg.setMessage(text);
        return msg;
    }

    // ---------- broadcast ----------

    @Test
    void textIsBroadcastToOtherMembersOfTheRoom() throws IOException {
        WebSocketSession alice = session("/chat/5");
        WebSocketSession bob = session("/chat/5");

        send(alice, payload("alice", "Joining", "JOIN"));
        send(bob, payload("bob", "Joining", "JOIN"));
        send(alice, payload("alice", "hello room", "TEXT"));

        JsonNode received = lastFrame(bob);
        assertThat(received.get("status").asText()).isEqualTo("BROADCAST");
        assertThat(received.get("message").asText()).isEqualTo("alice: hello room");

        // the sender still gets its own acknowledgement, not a broadcast copy
        assertThat(lastFrame(alice).get("status").asText()).isEqualTo("OK");
    }

    @Test
    void messagesAreNotBroadcastAcrossRooms() throws IOException {
        WebSocketSession alice = session("/chat/1");
        WebSocketSession bob = session("/chat/2");

        send(alice, payload("alice", "Joining", "JOIN"));
        send(bob, payload("bob", "Joining", "JOIN"));
        send(alice, payload("alice", "room one only", "TEXT"));

        // bob only ever saw his own join acknowledgement
        assertThat(sentFrames(bob)).hasSize(1);
    }

    @Test
    void broadcastCanBeDisabled() throws IOException {
        handler = new ChatServerWSHandler(validator, chatService, false);
        WebSocketSession alice = session("/chat/5");
        WebSocketSession bob = session("/chat/5");

        send(alice, payload("alice", "Joining", "JOIN"));
        send(bob, payload("bob", "Joining", "JOIN"));
        send(alice, payload("alice", "hello room", "TEXT"));

        assertThat(sentFrames(bob)).hasSize(1);
    }

    // ---------- lifecycle robustness ----------

    @Test
    void connectionLifecycleSurvivesAMissingUri() {
        WebSocketSession session = session(null);

        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    }

    @Test
    void nestedPathStillResolvesToTheRoomId() throws IOException {
        WebSocketSession session = session("/chat/42/extra");

        send(session, payload("alice", "Joining", "JOIN"));

        verify(chatService).getRecentMessages("42");
    }

    @Test
    void closingAConnectionClearsItsRoomMembership() throws IOException {
        WebSocketSession session = session("/chat/5");

        send(session, payload("alice", "Joining", "JOIN"));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        // membership is gone, so a subsequent TEXT is refused again
        send(session, payload("alice", "hello", "TEXT"));
        assertThat(lastFrame(session).get("status").asText()).isEqualTo("ERROR");
    }
}
