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
import org.springframework.web.socket.CloseStatus;
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
import static org.mockito.Mockito.when;

/**
 * The PRESENCE frame pushed to a room whenever its membership changes.
 */
class PresenceFrameTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger IDS = new AtomicInteger();

    private ChatServerWSHandler handler;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        handler = new ChatServerWSHandler(validator, mock(ChatService.class), true,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                new StreamlineProperties.RateLimit(),
                new ChatMetrics(new SimpleMeterRegistry()));
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
                {"userId":7,"username":"%s","message":"hello there","timestamp":"2026-08-07T10:00:00Z","messageType":"%s"}
                """.formatted(username, type)));
    }

    private List<JsonNode> presenceFrames(WebSocketSession session) throws IOException {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, captor);

        List<JsonNode> frames = new ArrayList<>();
        for (WebSocketMessage<?> frame : captor.getAllValues()) {
            JsonNode node = MAPPER.readTree(frame.getPayload().toString());
            if ("PRESENCE".equals(node.get("status").asText())) {
                frames.add(node);
            }
        }
        return frames;
    }

    private void verify(WebSocketSession session, ArgumentCaptor<WebSocketMessage<?>> captor)
            throws IOException {
        org.mockito.Mockito.verify(session, org.mockito.Mockito.atLeast(0))
                .sendMessage(captor.capture());
    }

    private String latestMembers(WebSocketSession session) throws IOException {
        List<JsonNode> frames = presenceFrames(session);
        return frames.isEmpty() ? null : frames.get(frames.size() - 1).get("message").asText();
    }

    @Test
    void joiningProducesAPresenceFrameForTheJoiner() throws IOException {
        WebSocketSession alice = session("general");

        send(alice, "alice", "JOIN");

        // the joiner needs the list too; nobody else would send it
        assertThat(latestMembers(alice)).isEqualTo("alice");
    }

    @Test
    void aSecondJoinUpdatesEveryone() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession bob = session("general");

        send(alice, "alice", "JOIN");
        send(bob, "bob", "JOIN");

        assertThat(latestMembers(alice)).isEqualTo("alice,bob");
        assertThat(latestMembers(bob)).isEqualTo("alice,bob");
    }

    @Test
    void leavingUpdatesTheRemainingMembers() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession bob = session("general");
        send(alice, "alice", "JOIN");
        send(bob, "bob", "JOIN");

        send(alice, "alice", "LEAVE");

        assertThat(latestMembers(bob)).isEqualTo("bob");
    }

    @Test
    void disconnectingUpdatesTheRemainingMembers() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession bob = session("general");
        send(alice, "alice", "JOIN");
        send(bob, "bob", "JOIN");

        handler.afterConnectionClosed(alice, CloseStatus.NORMAL);

        // a dropped client sends no LEAVE, so the close has to trigger the update
        assertThat(latestMembers(bob)).isEqualTo("bob");
    }

    @Test
    void presenceDoesNotCrossRooms() throws IOException {
        WebSocketSession alice = session("one");
        WebSocketSession bob = session("two");

        send(alice, "alice", "JOIN");
        send(bob, "bob", "JOIN");

        assertThat(latestMembers(alice)).isEqualTo("alice");
        assertThat(latestMembers(bob)).isEqualTo("bob");
    }

    @Test
    void plainTextMessagesDoNotTriggerPresenceUpdates() throws IOException {
        WebSocketSession alice = session("general");
        send(alice, "alice", "JOIN");
        int afterJoin = presenceFrames(alice).size();

        send(alice, "alice", "TEXT");

        // membership did not change, so no new frame
        assertThat(presenceFrames(alice)).hasSize(afterJoin);
    }

    @Test
    void aRejectedJoinProducesNoPresenceUpdate() throws IOException {
        WebSocketSession alice = session("general");
        send(alice, "alice", "JOIN");
        int afterJoin = presenceFrames(alice).size();

        send(alice, "alice", "JOIN"); // already joined, refused

        assertThat(presenceFrames(alice)).hasSize(afterJoin);
    }

    @Test
    void theFrameCarriesAServerTimestampLikeEveryOther() throws IOException {
        WebSocketSession alice = session("general");

        send(alice, "alice", "JOIN");

        assertThat(presenceFrames(alice).get(0).hasNonNull("serverTimestamp")).isTrue();
    }

    @Test
    void closingTheLastMemberProducesNoFrameForAnEmptyRoom() throws IOException {
        WebSocketSession alice = session("general");
        send(alice, "alice", "JOIN");
        int afterJoin = presenceFrames(alice).size();

        handler.afterConnectionClosed(alice, CloseStatus.NORMAL);

        // nobody is left to tell, and the closed session must not be written to
        assertThat(presenceFrames(alice)).hasSize(afterJoin);
    }
}
