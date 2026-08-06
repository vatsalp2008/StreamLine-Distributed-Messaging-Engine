package server.configure;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import server.service.ChatService;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomPresenceTest {

    private static final AtomicInteger SESSION_IDS = new AtomicInteger();

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
        when(session.getId()).thenReturn("session-" + SESSION_IDS.incrementAndGet());
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/chat/" + room));
        return session;
    }

    private void send(WebSocketSession session, String username, String type) throws IOException {
        handler.handleMessage(session, new TextMessage("""
                {"userId":7,"username":"%s","message":"hello there","timestamp":"2026-08-06T10:00:00Z","messageType":"%s"}
                """.formatted(username, type)));
    }

    @Test
    void anEmptyRoomHasNoMembers() {
        assertThat(handler.getRoomMembers("general")).isEmpty();
    }

    @Test
    void joiningAddsTheUsername() throws IOException {
        send(session("general"), "alice", "JOIN");

        assertThat(handler.getRoomMembers("general")).containsExactly("alice");
    }

    @Test
    void membersAreListedAlphabetically() throws IOException {
        send(session("general"), "carol", "JOIN");
        send(session("general"), "alice", "JOIN");
        send(session("general"), "bob", "JOIN");

        assertThat(handler.getRoomMembers("general")).containsExactly("alice", "bob", "carol");
    }

    @Test
    void membersAreScopedToTheirOwnRoom() throws IOException {
        send(session("one"), "alice", "JOIN");
        send(session("two"), "bob", "JOIN");

        assertThat(handler.getRoomMembers("one")).containsExactly("alice");
        assertThat(handler.getRoomMembers("two")).containsExactly("bob");
    }

    @Test
    void theSameUsernameConnectedTwiceIsListedOnce() throws IOException {
        send(session("general"), "alice", "JOIN");
        send(session("general"), "alice", "JOIN");

        // presence answers "who is here", so duplicate connections collapse
        assertThat(handler.getRoomMembers("general")).containsExactly("alice");
        // but both sockets are still counted as sessions
        assertThat(handler.getRoomOccupancy()).containsEntry("general", 2);
    }

    @Test
    void leavingRemovesTheUsername() throws IOException {
        WebSocketSession alice = session("general");
        send(alice, "alice", "JOIN");
        send(alice, "alice", "LEAVE");

        assertThat(handler.getRoomMembers("general")).isEmpty();
    }

    @Test
    void disconnectingRemovesTheUsername() throws IOException {
        WebSocketSession alice = session("general");
        send(alice, "alice", "JOIN");

        handler.afterConnectionClosed(alice, CloseStatus.NORMAL);

        // a dropped connection must not leave a ghost in the member list
        assertThat(handler.getRoomMembers("general")).isEmpty();
    }

    @Test
    void oneUserLeavingDoesNotRemoveTheOthers() throws IOException {
        WebSocketSession alice = session("general");
        send(alice, "alice", "JOIN");
        send(session("general"), "bob", "JOIN");

        send(alice, "alice", "LEAVE");

        assertThat(handler.getRoomMembers("general")).containsExactly("bob");
    }

    @Test
    void aUserWhoNeverJoinedIsNotAMember() throws IOException {
        // TEXT without JOIN is refused, so it must not create presence
        send(session("general"), "alice", "TEXT");

        assertThat(handler.getRoomMembers("general")).isEmpty();
    }

    @Test
    void anUnknownRoomReturnsAnEmptyListRatherThanFailing() {
        assertThat(handler.getRoomMembers("never-used")).isEmpty();
    }

    @Test
    void theReturnedListCannotBeModifiedByCallers() throws IOException {
        send(session("general"), "alice", "JOIN");

        assertThat(handler.getRoomMembers("general")).isUnmodifiable();
    }
}
