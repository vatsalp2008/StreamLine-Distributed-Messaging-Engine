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
import server.model.ChatMessage;
import server.service.ChatService;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which frames name the stored message they refer to.
 *
 * A client can only apply an edit or a deletion to a line it can identify. Until
 * these ids existed that meant its own messages only, because a delivery receipt
 * was the sole source of an id.
 */
class MessageIdFrameTest {

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

    private ChatMessage stored(Long id, String username, String text) {
        ChatMessage msg = new ChatMessage();
        msg.setId(id);
        msg.setUsername(username);
        msg.setMessage(text);
        msg.setMessageType("TEXT");
        msg.setTimestamp(Instant.parse("2026-08-13T10:00:00Z"));
        return msg;
    }

    private void join(WebSocketSession session, String username) throws IOException {
        handler.handleMessage(session, new TextMessage("""
                {"userId":7,"username":"%s","message":"Joining","timestamp":"2026-08-13T10:00:00Z","messageType":"JOIN"}
                """.formatted(username)));
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

    private List<JsonNode> framesOfStatus(WebSocketSession session, String status)
            throws IOException {
        return framesTo(session).stream()
                .filter(f -> status.equals(f.get("status").asText()))
                .toList();
    }

    @Test
    void replayedHistoryNamesEachStoredMessage() throws IOException {
        when(chatService.getRecentMessages("general")).thenReturn(List.of(
                stored(20L, "bob", "newest"), stored(10L, "carol", "oldest")));
        WebSocketSession session = session("general");

        join(session, "alice");

        List<JsonNode> history = framesOfStatus(session, "HISTORY");
        assertThat(history).hasSize(2);
        // replayed oldest first, so the ids come back in ascending order
        assertThat(history.get(0).get("messageId").asLong()).isEqualTo(10L);
        assertThat(history.get(1).get("messageId").asLong()).isEqualTo(20L);
    }

    @Test
    void historyForAMessageWithNoIdOmitsTheField() throws IOException {
        when(chatService.getRecentMessages("general"))
                .thenReturn(List.of(stored(null, "bob", "unsaved")));
        WebSocketSession session = session("general");

        join(session, "alice");

        assertThat(framesOfStatus(session, "HISTORY").get(0).has("messageId")).isFalse();
    }

    @Test
    void anEditNamesTheMessageItChanged() throws IOException {
        WebSocketSession session = session("general");
        join(session, "alice");

        handler.announceEdit("general", 42L, "corrected");

        JsonNode edit = framesOfStatus(session, "EDITED").get(0);
        assertThat(edit.get("messageId").asLong()).isEqualTo(42L);
    }

    @Test
    void aRedactionNamesTheMessageItRemoved() throws IOException {
        WebSocketSession session = session("general");
        join(session, "alice");

        handler.announceRedaction("general", 42L);

        assertThat(framesOfStatus(session, "REDACTED").get(0).get("messageId").asLong())
                .isEqualTo(42L);
    }

    @Test
    void anEditStillCarriesTheTextItAppliedInTheBody() throws IOException {
        WebSocketSession session = session("general");
        join(session, "alice");

        handler.announceEdit("general", 42L, "corrected");

        // the body keeps the id:text form so a client updated for the field is
        // not required in order to read the new text
        assertThat(framesOfStatus(session, "EDITED").get(0).get("message").asText())
                .isEqualTo("42:corrected");
    }

    @Test
    void framesThatReferToNoStoredMessageCarryNoId() throws IOException {
        WebSocketSession session = session("general");

        join(session, "alice");

        for (JsonNode frame : framesTo(session)) {
            String status = frame.get("status").asText();
            if ("OK".equals(status) || "PRESENCE".equals(status)) {
                assertThat(frame.has("messageId"))
                        .as("%s refers to no stored message", status)
                        .isFalse();
            }
        }
    }

    @Test
    void anEditReachesEveryMemberOfTheRoom() throws IOException {
        WebSocketSession alice = session("general");
        WebSocketSession bob = session("general");
        join(alice, "alice");
        join(bob, "bob");

        handler.announceEdit("general", 42L, "corrected");

        // an edit is not only for the author; everyone is showing the old text
        assertThat(framesOfStatus(alice, "EDITED")).hasSize(1);
        assertThat(framesOfStatus(bob, "EDITED")).hasSize(1);
    }
}
