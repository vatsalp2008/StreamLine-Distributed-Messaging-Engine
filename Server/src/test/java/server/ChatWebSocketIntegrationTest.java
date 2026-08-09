package server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real WebSocket stack end to end: handshake, validation,
 * acknowledgement, and fan-out between two live clients.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:streamline-it;DB_CLOSE_DELAY=-1",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class ChatWebSocketIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 10;

    @LocalServerPort
    private int port;

    /** A connected client that records every frame the server sends it. */
    private static final class RecordingClient {
        private final WebSocketSession session;
        private final BlockingQueue<String> frames;

        RecordingClient(WebSocketSession session, BlockingQueue<String> frames) {
            this.session = session;
            // share the queue the handler writes into, do not snapshot it
            this.frames = frames;
        }

        void send(String json) throws Exception {
            session.sendMessage(new TextMessage(json));
        }

        JsonNode nextFrame() throws Exception {
            String payload = frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(payload).as("expected a frame from the server").isNotNull();
            return MAPPER.readTree(payload);
        }

        /**
         * Reads past informational frames to the next acknowledgement or delivery.
         *
         * HISTORY and PRESENCE arrive unpredictably: how much history a room has
         * depends on what earlier tests left in the shared database, and a
         * PRESENCE frame is emitted whenever anyone joins or leaves. Neither is
         * what these tests are asserting on.
         */
        JsonNode nextAck() throws Exception {
            for (int i = 0; i < 60; i++) {
                JsonNode frame = nextFrame();
                String status = frame.get("status").asText();
                if (!"HISTORY".equals(status) && !"PRESENCE".equals(status)) {
                    return frame;
                }
            }
            throw new AssertionError("only history frames arrived, never an acknowledgement");
        }

        /**
         * True when no chat traffic arrives in the window.
         *
         * PRESENCE frames are not chat: a client gets them for its own room's
         * membership, so their absence is not what "did not receive the other
         * room's message" means.
         */
        boolean receivedNoChatWithin(long millis) throws Exception {
            long deadline = System.currentTimeMillis() + millis;
            String payload;
            while ((payload = frames.poll(Math.max(deadline - System.currentTimeMillis(), 0),
                    TimeUnit.MILLISECONDS)) != null) {
                String status = MAPPER.readTree(payload).get("status").asText();
                if (!"PRESENCE".equals(status) && !"HISTORY".equals(status)) {
                    return false;
                }
            }
            return true;
        }

        void close() throws Exception {
            session.close(CloseStatus.NORMAL);
        }
    }

    private RecordingClient connect(String room) throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();

        WebSocketSession session = new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                        frames.add(message.getPayload());
                    }
                }, "ws://localhost:{port}/chat/{room}", port, room)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        return new RecordingClient(session, frames);
    }

    private static String frame(String username, String message, String type) {
        return """
                {"userId":7,"username":"%s","message":"%s","timestamp":"2026-08-05T10:00:00Z","messageType":"%s"}
                """.formatted(username, message, type);
    }

    @Test
    void joinIsAcknowledgedOverARealConnection() throws Exception {
        RecordingClient client = connect("it-join");
        try {
            client.send(frame("alice", "Joining", "JOIN"));

            JsonNode response = client.nextAck();
            assertThat(response.get("status").asText()).isEqualTo("OK");
            assertThat(response.get("message").asText()).isEqualTo("alice joined the room");
        } finally {
            client.close();
        }
    }

    @Test
    void textBeforeJoinIsRefused() throws Exception {
        RecordingClient client = connect("it-order");
        try {
            client.send(frame("alice", "hello", "TEXT"));

            JsonNode response = client.nextFrame();
            assertThat(response.get("status").asText()).isEqualTo("ERROR");
            assertThat(response.get("message").asText()).isEqualTo("You must JOIN before sending TEXT");
        } finally {
            client.close();
        }
    }

    @Test
    void invalidPayloadIsRejectedWithoutDroppingTheConnection() throws Exception {
        RecordingClient client = connect("it-invalid");
        try {
            client.send(frame("x", "hello", "JOIN")); // username shorter than 3 chars

            JsonNode response = client.nextFrame();
            assertThat(response.get("status").asText()).isEqualTo("ERROR");
            assertThat(response.get("message").asText()).startsWith("Validation failed");

            // the socket is still usable afterwards
            client.send(frame("alice", "Joining", "JOIN"));
            assertThat(client.nextAck().get("status").asText()).isEqualTo("OK");
        } finally {
            client.close();
        }
    }

    @Test
    void messagesReachOtherClientsInTheSameRoom() throws Exception {
        RecordingClient alice = connect("it-broadcast");
        RecordingClient bob = connect("it-broadcast");
        try {
            alice.send(frame("alice", "Joining", "JOIN"));
            assertThat(alice.nextAck().get("status").asText()).isEqualTo("OK");

            bob.send(frame("bob", "Joining", "JOIN"));
            assertThat(bob.nextAck().get("status").asText()).isEqualTo("OK");

            // alice sees bob's join announcement fanned out to her
            assertThat(alice.nextAck().get("status").asText()).isEqualTo("BROADCAST");

            alice.send(frame("alice", "hello room", "TEXT"));

            JsonNode delivered = bob.nextAck();
            assertThat(delivered.get("status").asText()).isEqualTo("BROADCAST");
            assertThat(delivered.get("message").asText()).isEqualTo("alice: hello room");
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    void messagesDoNotLeakIntoOtherRooms() throws Exception {
        RecordingClient alice = connect("it-room-a");
        RecordingClient bob = connect("it-room-b");
        try {
            alice.send(frame("alice", "Joining", "JOIN"));
            assertThat(alice.nextAck().get("status").asText()).isEqualTo("OK");

            bob.send(frame("bob", "Joining", "JOIN"));
            assertThat(bob.nextAck().get("status").asText()).isEqualTo("OK");

            alice.send(frame("alice", "private to room a", "TEXT"));
            assertThat(alice.nextAck().get("status").asText()).isEqualTo("OK");

            assertThat(bob.receivedNoChatWithin(750)).isTrue();
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    void historyIsReplayedToAClientJoiningLater() throws Exception {
        RecordingClient first = connect("it-history");
        first.send(frame("alice", "Joining", "JOIN"));
        assertThat(first.nextAck().get("status").asText()).isEqualTo("OK");
        first.send(frame("alice", "persisted line", "TEXT"));
        assertThat(first.nextAck().get("status").asText()).isEqualTo("OK");
        first.close();

        // persistence is async, so give the writer a moment to flush
        Thread.sleep(1500);

        RecordingClient second = connect("it-history");
        try {
            second.send(frame("bob", "Joining", "JOIN"));

            boolean sawHistory = false;
            for (int i = 0; i < 10; i++) {
                JsonNode response = second.nextFrame();
                if ("HISTORY".equals(response.get("status").asText())) {
                    sawHistory = true;
                    break;
                }
                if ("OK".equals(response.get("status").asText())) {
                    break;
                }
            }
            assertThat(sawHistory).as("joining client should receive replayed history").isTrue();
        } finally {
            second.close();
        }
    }

    // ---------- correlation ids ----------

    /** A frame carrying a client-supplied correlation id. */
    private static String frameWithId(String username, String message, String type, String id) {
        return """
                {"userId":7,"username":"%s","message":"%s","timestamp":"2026-08-09T10:00:00Z","messageType":"%s","clientId":"%s"}
                """.formatted(username, message, type, id);
    }

    @Test
    void acknowledgementsCarryTheSendersCorrelationId() throws Exception {
        RecordingClient client = connect("it-correlate");
        try {
            client.send(frameWithId("alice", "Joining", "JOIN", "c-1"));

            JsonNode ack = client.nextAck();
            assertThat(ack.get("status").asText()).isEqualTo("OK");
            assertThat(ack.get("clientId").asText()).isEqualTo("c-1");
        } finally {
            client.close();
        }
    }

    @Test
    void aSenderCanTellItsOwnAckApartFromAnotherClientsTraffic() throws Exception {
        RecordingClient alice = connect("it-correlate-two");
        RecordingClient bob = connect("it-correlate-two");
        try {
            alice.send(frameWithId("alice", "Joining", "JOIN", "a-1"));
            assertThat(alice.nextAck().get("clientId").asText()).isEqualTo("a-1");

            bob.send(frameWithId("bob", "Joining", "JOIN", "b-1"));
            assertThat(bob.nextAck().get("clientId").asText()).isEqualTo("b-1");

            // bob's join fans out to alice; that frame answers nothing alice sent
            JsonNode fanOut = alice.nextAck();
            assertThat(fanOut.get("status").asText()).isEqualTo("BROADCAST");
            assertThat(fanOut.has("clientId")).isFalse();

            alice.send(frameWithId("alice", "hello", "TEXT", "a-2"));
            JsonNode ownAck = alice.nextAck();
            assertThat(ownAck.get("status").asText()).isEqualTo("OK");
            assertThat(ownAck.get("clientId").asText()).isEqualTo("a-2");
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    void aRefusalOverARealConnectionCarriesTheId() throws Exception {
        RecordingClient client = connect("it-correlate-error");
        try {
            // TEXT before JOIN is refused
            client.send(frameWithId("alice", "hello", "TEXT", "doomed"));

            JsonNode reply = client.nextFrame();
            assertThat(reply.get("status").asText()).isEqualTo("ERROR");
            assertThat(reply.get("clientId").asText()).isEqualTo("doomed");
        } finally {
            client.close();
        }
    }

    @Test
    void aClientThatSendsNoIdStillWorks() throws Exception {
        RecordingClient client = connect("it-correlate-none");
        try {
            client.send(frame("alice", "Joining", "JOIN"));

            JsonNode ack = client.nextAck();
            assertThat(ack.get("status").asText()).isEqualTo("OK");
            assertThat(ack.has("clientId")).isFalse();
        } finally {
            client.close();
        }
    }
}
