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
 * Editing a message against a running server.
 *
 * Covers what no unit test can: that the id a receipt hands out is the one the
 * edit endpoint accepts, and that a client still in the room is told what the
 * message now says.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:streamline-edit;DB_CLOSE_DELAY=-1",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "streamline.receipts.enabled=true"
        })
class EditIntegrationTest {

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
    void aStoredMessageCanBeEditedByTheIdItsReceiptReported() throws Exception {
        Client client = connect("edit-basic");
        try {
            String id = storeMessage(client, "alice", "original wording");

            org.springframework.http.ResponseEntity<String> response = rest.exchange(
                    "/api/rooms/edit-basic/messages/" + id, HttpMethod.PATCH,
                    new org.springframework.http.HttpEntity<>(
                            "{\"message\":\"corrected wording\"}", jsonHeaders()),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("corrected wording");
            assertThat(rest.getForObject("/api/rooms/edit-basic/messages", String.class))
                    .contains("corrected wording")
                    .doesNotContain("original wording");
        } finally {
            client.close();
        }
    }

    @Test
    void theRoomIsToldWhatTheMessageNowSays() throws Exception {
        Client client = connect("edit-notify");
        try {
            String id = storeMessage(client, "bob", "before");

            rest.exchange("/api/rooms/edit-notify/messages/" + id, HttpMethod.PATCH,
                    new org.springframework.http.HttpEntity<>(
                            "{\"message\":\"after\"}", jsonHeaders()), String.class);

            // id and new text together, so a client can update without re-reading
            assertThat(client.await("EDITED").get("message").asText())
                    .isEqualTo(id + ":after");
        } finally {
            client.close();
        }
    }

    @Test
    void aMessageCannotBeEditedThroughAnotherRoom() throws Exception {
        Client client = connect("edit-owner");
        try {
            String id = storeMessage(client, "carol", "belongs to owner");

            assertThat(rest.exchange("/api/rooms/edit-other/messages/" + id, HttpMethod.PATCH,
                    new org.springframework.http.HttpEntity<>(
                            "{\"message\":\"hijacked\"}", jsonHeaders()), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            assertThat(rest.getForObject("/api/rooms/edit-owner/messages", String.class))
                    .contains("belongs to owner");
        } finally {
            client.close();
        }
    }

    @Test
    void anEmptyBodyIsRejected() throws Exception {
        Client client = connect("edit-empty");
        try {
            String id = storeMessage(client, "dave", "something");

            assertThat(rest.exchange("/api/rooms/edit-empty/messages/" + id, HttpMethod.PATCH,
                    new org.springframework.http.HttpEntity<>(
                            "{\"message\":\"\"}", jsonHeaders()), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            client.close();
        }
    }

    @Test
    void anUnknownIdIsReportedAsMissing() {
        assertThat(rest.exchange("/api/rooms/edit-basic/messages/999999", HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(
                        "{\"message\":\"nothing\"}", jsonHeaders()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static org.springframework.http.HttpHeaders jsonHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }
}
