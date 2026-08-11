package server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
 * Deleting a message against a running server.
 *
 * Covers the part no unit test can: that the id a receipt hands out is the one
 * the delete endpoint accepts, and that a client still in the room is told.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:streamline-deletion;DB_CLOSE_DELAY=-1",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "streamline.receipts.enabled=true"
        })
class DeletionIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 10;

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    private record Client(WebSocketSession session, BlockingQueue<String> frames) {
        void send(String json) throws Exception {
            session.sendMessage(new TextMessage(json));
        }

        JsonNode await(String status) throws Exception {
            for (int i = 0; i < 60; i++) {
                String payload = frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(payload).as("expected a %s frame", status).isNotNull();
                JsonNode frame = MAPPER.readTree(payload);
                if (status.equals(frame.get("status").asText())) {
                    return frame;
                }
            }
            throw new AssertionError("never saw a " + status + " frame");
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

    private static String frame(String username, String text, String type, String clientId) {
        String correlation = clientId == null ? "" : ",\"clientId\":\"%s\"".formatted(clientId);
        return """
                {"userId":7,"username":"%s","message":"%s","timestamp":"2026-08-11T10:00:00Z","messageType":"%s"%s}
                """.formatted(username, text, type, correlation);
    }

    /** Sends one message and returns the id its receipt reported. */
    private String storeMessage(Client client, String username, String text) throws Exception {
        client.send(frame(username, "Joining", "JOIN", null));
        client.await("OK");
        client.send(frame(username, text, "TEXT", "m-1"));
        return client.await("DELIVERED").get("message").asText();
    }

    @Test
    void aStoredMessageCanBeDeletedByTheIdItsReceiptReported() throws Exception {
        Client client = connect("del-basic");
        try {
            String id = storeMessage(client, "alice", "delete me");

            assertThat(rest.exchange("/api/rooms/del-basic/messages/" + id,
                    HttpMethod.DELETE, null, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);

            assertThat(rest.getForObject("/api/rooms/del-basic/messages", String.class))
                    .doesNotContain("delete me");
        } finally {
            client.close();
        }
    }

    @Test
    void theRoomIsToldThatTheMessageWasRemoved() throws Exception {
        Client client = connect("del-notify");
        try {
            String id = storeMessage(client, "bob", "goodbye");

            rest.exchange("/api/rooms/del-notify/messages/" + id,
                    HttpMethod.DELETE, null, Void.class);

            // a client already showing the message has no other way to learn
            assertThat(client.await("REDACTED").get("message").asText()).isEqualTo(id);
        } finally {
            client.close();
        }
    }

    @Test
    void deletingTheSameMessageTwiceReportsItIsGone() throws Exception {
        Client client = connect("del-twice");
        try {
            String id = storeMessage(client, "carol", "once only");
            rest.exchange("/api/rooms/del-twice/messages/" + id,
                    HttpMethod.DELETE, null, Void.class);

            assertThat(rest.exchange("/api/rooms/del-twice/messages/" + id,
                    HttpMethod.DELETE, null, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            client.close();
        }
    }

    @Test
    void aMessageCannotBeDeletedThroughAnotherRoom() throws Exception {
        Client client = connect("del-owner");
        try {
            String id = storeMessage(client, "dave", "belongs to owner");

            assertThat(rest.exchange("/api/rooms/del-other/messages/" + id,
                    HttpMethod.DELETE, null, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            // and it is still there
            assertThat(rest.getForObject("/api/rooms/del-owner/messages", String.class))
                    .contains("belongs to owner");
        } finally {
            client.close();
        }
    }

    @Test
    void anUnknownIdIsReportedAsMissing() {
        assertThat(rest.exchange("/api/rooms/del-basic/messages/999999",
                HttpMethod.DELETE, null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
