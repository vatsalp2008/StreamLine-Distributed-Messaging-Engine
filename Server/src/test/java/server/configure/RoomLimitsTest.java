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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Caps on how much room state one server will hold.
 *
 * Room ids are taken from the connection URL, so without a cap a client can make
 * the server allocate rooms indefinitely just by connecting to new paths.
 */
class RoomLimitsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger IDS = new AtomicInteger();

    private Validator validator;
    private ChatServerWSHandler handler;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        handler = handlerWith(new StreamlineProperties.Limits());
    }

    private ChatServerWSHandler handlerWith(StreamlineProperties.Limits limits) {
        return new ChatServerWSHandler(validator, mock(ChatService.class), true,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                new StreamlineProperties.RateLimit(),
                new ChatMetrics(new SimpleMeterRegistry()),
                new StreamlineProperties.Identity(),
                limits);
    }

    private static StreamlineProperties.Limits limits(int maxRooms, int maxMembers) {
        StreamlineProperties.Limits limits = new StreamlineProperties.Limits();
        limits.setMaxRooms(maxRooms);
        limits.setMaxMembersPerRoom(maxMembers);
        return limits;
    }

    private WebSocketSession session(String room) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("s" + IDS.incrementAndGet());
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/chat/" + room));
        return session;
    }

    private void join(WebSocketSession session, String username) throws IOException {
        handler.handleMessage(session, new TextMessage("""
                {"userId":7,"username":"%s","message":"hello there","timestamp":"2026-08-09T10:00:00Z","messageType":"JOIN"}
                """.formatted(username)));
    }

    private JsonNode lastReply(WebSocketSession session) throws IOException {
        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());

        List<JsonNode> direct = new ArrayList<>();
        for (WebSocketMessage<?> frame : captor.getAllValues()) {
            JsonNode node = MAPPER.readTree(frame.getPayload().toString());
            String status = node.get("status").asText();
            if ("OK".equals(status) || "ERROR".equals(status)) {
                direct.add(node);
            }
        }
        return direct.get(direct.size() - 1);
    }

    // ---------- room cap ----------

    @Test
    void roomsUpToTheCapAreAccepted() throws IOException {
        handler = handlerWith(limits(3, 0));

        join(session("a"), "alice");
        join(session("b"), "bob");
        WebSocketSession third = session("c");
        join(third, "carol");

        assertThat(lastReply(third).get("status").asText()).isEqualTo("OK");
        assertThat(handler.getActiveRoomCount()).isEqualTo(3);
    }

    @Test
    void aRoomBeyondTheCapIsRefused() throws IOException {
        handler = handlerWith(limits(2, 0));
        join(session("a"), "alice");
        join(session("b"), "bob");

        WebSocketSession overflow = session("c");
        join(overflow, "carol");

        JsonNode reply = lastReply(overflow);
        assertThat(reply.get("status").asText()).isEqualTo("ERROR");
        assertThat(reply.get("message").asText()).contains("room limit of 2");
    }

    @Test
    void aRefusedJoinAllocatesNoRoom() throws IOException {
        handler = handlerWith(limits(1, 0));
        join(session("a"), "alice");

        join(session("b"), "bob");

        // the refusal must not leave a half-created room behind
        assertThat(handler.getActiveRoomCount()).isEqualTo(1);
        assertThat(handler.getRoomOccupancy()).containsOnlyKeys("a");
    }

    @Test
    void joiningAnExistingRoomIsAllowedAtTheRoomCap() throws IOException {
        handler = handlerWith(limits(1, 0));
        join(session("a"), "alice");

        WebSocketSession second = session("a");
        join(second, "bob");

        // the cap is on rooms, and this adds no new room
        assertThat(lastReply(second).get("status").asText()).isEqualTo("OK");
    }

    @Test
    void aRoomFreedByDepartureCanBeReplaced() throws IOException {
        handler = handlerWith(limits(1, 0));
        WebSocketSession alice = session("a");
        join(alice, "alice");
        handler.afterConnectionClosed(alice, CloseStatus.NORMAL);

        WebSocketSession newcomer = session("b");
        join(newcomer, "bob");

        assertThat(lastReply(newcomer).get("status").asText()).isEqualTo("OK");
    }

    // ---------- member cap ----------

    @Test
    void membersUpToTheCapAreAccepted() throws IOException {
        handler = handlerWith(limits(0, 2));

        join(session("a"), "alice");
        WebSocketSession second = session("a");
        join(second, "bob");

        assertThat(lastReply(second).get("status").asText()).isEqualTo("OK");
    }

    @Test
    void aMemberBeyondTheRoomCapIsRefused() throws IOException {
        handler = handlerWith(limits(0, 2));
        join(session("a"), "alice");
        join(session("a"), "bob");

        WebSocketSession overflow = session("a");
        join(overflow, "carol");

        JsonNode reply = lastReply(overflow);
        assertThat(reply.get("status").asText()).isEqualTo("ERROR");
        assertThat(reply.get("message").asText()).contains("Room is full");
    }

    @Test
    void aFullRoomDoesNotBlockAnotherRoom() throws IOException {
        handler = handlerWith(limits(0, 1));
        join(session("a"), "alice");

        WebSocketSession elsewhere = session("b");
        join(elsewhere, "bob");

        assertThat(lastReply(elsewhere).get("status").asText()).isEqualTo("OK");
    }

    // ---------- disabling ----------

    @Test
    void zeroMeansUnlimited() throws IOException {
        handler = handlerWith(limits(0, 0));

        for (int i = 0; i < 25; i++) {
            join(session("room" + i), "user" + i);
        }

        assertThat(handler.getActiveRoomCount()).isEqualTo(25);
    }

    @Test
    void theDefaultsAllowOrdinaryUse() throws IOException {
        // defaults must not get in the way of a normal small deployment
        for (int i = 0; i < 20; i++) {
            join(session("room" + i), "user" + i);
        }

        assertThat(handler.getActiveRoomCount()).isEqualTo(20);
    }
}
