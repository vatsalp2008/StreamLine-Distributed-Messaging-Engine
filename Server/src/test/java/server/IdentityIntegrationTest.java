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
 * Identity rules over a real connection.
 *
 * The unit tests drive the handler directly; this checks the rules survive the
 * full stack, including that a refused frame never reaches other members.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:streamline-identity-it;DB_CLOSE_DELAY=-1",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false"
        })
class IdentityIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 10;

    @LocalServerPort
    private int port;

    private record Client(WebSocketSession session, BlockingQueue<String> frames) {
        void send(String json) throws Exception {
            session.sendMessage(new TextMessage(json));
        }

        /** Next frame that is not a presence update. */
        JsonNode nextReply() throws Exception {
            for (int i = 0; i < 60; i++) {
                String payload = frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(payload).as("expected a frame from the server").isNotNull();
                JsonNode frame = MAPPER.readTree(payload);
                String status = frame.get("status").asText();
                if (!"PRESENCE".equals(status) && !"HISTORY".equals(status)) {
                    return frame;
                }
            }
            throw new AssertionError("never received a reply");
        }

        boolean sawNoChatWithin(long millis) throws Exception {
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

    private Client connect(String room) throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();

        WebSocketSession session = new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                        frames.add(message.getPayload());
                    }
                }, "ws://localhost:{port}/chat/{room}", port, room)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        return new Client(session, frames);
    }

    private static String frame(String username, String message, String type) {
        return """
                {"userId":7,"username":"%s","message":"%s","timestamp":"2026-08-08T10:00:00Z","messageType":"%s"}
                """.formatted(username, message, type);
    }

    @Test
    void aSessionCannotSendAsSomeoneElse() throws Exception {
        Client client = connect("id-impersonate");
        try {
            client.send(frame("alice", "Joining", "JOIN"));
            assertThat(client.nextReply().get("status").asText()).isEqualTo("OK");

            client.send(frame("mallory", "not really me", "TEXT"));

            JsonNode response = client.nextReply();
            assertThat(response.get("status").asText()).isEqualTo("ERROR");
            assertThat(response.get("message").asText()).contains("does not match the session");
        } finally {
            client.close();
        }
    }

    @Test
    void anImpersonatedMessageNeverReachesOtherMembers() throws Exception {
        Client alice = connect("id-fanout");
        Client bob = connect("id-fanout");
        try {
            alice.send(frame("alice", "Joining", "JOIN"));
            assertThat(alice.nextReply().get("status").asText()).isEqualTo("OK");
            bob.send(frame("bob", "Joining", "JOIN"));
            assertThat(bob.nextReply().get("status").asText()).isEqualTo("OK");

            // bob sees alice's join announcement fanned out
            assertThat(alice.nextReply().get("status").asText()).isEqualTo("BROADCAST");

            alice.send(frame("mallory", "spoofed", "TEXT"));
            assertThat(alice.nextReply().get("status").asText()).isEqualTo("ERROR");

            assertThat(bob.sawNoChatWithin(750)).isTrue();
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    void aUsernameAlreadyInTheRoomIsRefused() throws Exception {
        Client alice = connect("id-unique");
        Client impostor = connect("id-unique");
        try {
            alice.send(frame("alice", "Joining", "JOIN"));
            assertThat(alice.nextReply().get("status").asText()).isEqualTo("OK");

            impostor.send(frame("alice", "Joining", "JOIN"));

            JsonNode response = impostor.nextReply();
            assertThat(response.get("status").asText()).isEqualTo("ERROR");
            assertThat(response.get("message").asText()).contains("already in use");
        } finally {
            alice.close();
            impostor.close();
        }
    }

    @Test
    void theSameUsernameIsFineInAnotherRoom() throws Exception {
        Client here = connect("id-room-a");
        Client there = connect("id-room-b");
        try {
            here.send(frame("alice", "Joining", "JOIN"));
            assertThat(here.nextReply().get("status").asText()).isEqualTo("OK");

            there.send(frame("alice", "Joining", "JOIN"));
            assertThat(there.nextReply().get("status").asText()).isEqualTo("OK");
        } finally {
            here.close();
            there.close();
        }
    }

    @Test
    void aUsernameBecomesAvailableAgainAfterItsHolderDisconnects() throws Exception {
        Client first = connect("id-reuse");
        first.send(frame("alice", "Joining", "JOIN"));
        assertThat(first.nextReply().get("status").asText()).isEqualTo("OK");
        first.close();

        // give the close time to propagate through the handler
        Thread.sleep(750);

        Client second = connect("id-reuse");
        try {
            second.send(frame("alice", "Joining", "JOIN"));
            assertThat(second.nextReply().get("status").asText()).isEqualTo("OK");
        } finally {
            second.close();
        }
    }
}
