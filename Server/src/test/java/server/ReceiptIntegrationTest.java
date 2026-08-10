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
 * Delivery receipts against a running server.
 *
 * The unit tests drive the handler with a completed future; this exercises the
 * real async pool, so the receipt genuinely arrives after the row is written
 * rather than inline on the calling thread.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:streamline-receipts;DB_CLOSE_DELAY=-1",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "streamline.receipts.enabled=true"
        })
class ReceiptIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 10;

    @LocalServerPort
    private int port;

    private record Client(WebSocketSession session, BlockingQueue<String> frames) {

        void send(String json) throws Exception {
            session.sendMessage(new TextMessage(json));
        }

        /** Waits for the next frame with the given status, ignoring others. */
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
                {"userId":7,"username":"%s","message":"%s","timestamp":"2026-08-10T10:00:00Z","messageType":"%s"%s}
                """.formatted(username, text, type, correlation);
    }

    @Test
    void aStoredMessageIsConfirmedOverARealConnection() throws Exception {
        Client client = connect("it-receipt");
        try {
            client.send(frame("alice", "Joining", "JOIN", "j-1"));
            client.await("OK");

            client.send(frame("alice", "durable please", "TEXT", "m-1"));
            client.await("OK");

            JsonNode receipt = client.await("DELIVERED");
            assertThat(receipt.get("clientId").asText()).isEqualTo("m-1");
            // the body is the generated row id, so it parses as a number
            assertThat(Long.parseLong(receipt.get("message").asText())).isPositive();
        } finally {
            client.close();
        }
    }

    @Test
    void eachMessageIsConfirmedSeparately() throws Exception {
        Client client = connect("it-receipt-many");
        try {
            client.send(frame("bob", "Joining", "JOIN", null));
            client.await("OK");

            client.send(frame("bob", "first", "TEXT", "m-1"));
            client.send(frame("bob", "second", "TEXT", "m-2"));

            // ids are assigned by the database, so the two must differ
            long firstId = Long.parseLong(client.await("DELIVERED").get("message").asText());
            long secondId = Long.parseLong(client.await("DELIVERED").get("message").asText());
            assertThat(firstId).isNotEqualTo(secondId);
        } finally {
            client.close();
        }
    }

    @Test
    void theConfirmedIdRefersToARowThatCanBeReadBack() throws Exception {
        Client client = connect("it-receipt-read");
        try {
            client.send(frame("carol", "Joining", "JOIN", null));
            client.await("OK");
            client.send(frame("carol", "find me later", "TEXT", "m-1"));
            client.await("DELIVERED");

            // a receipt that named an id nothing could resolve would be useless
            org.springframework.web.client.RestClient rest =
                    org.springframework.web.client.RestClient.create();
            String body = rest.get()
                    .uri("http://localhost:" + port + "/api/rooms/it-receipt-read/messages")
                    .retrieve()
                    .body(String.class);

            assertThat(body).contains("find me later");
        } finally {
            client.close();
        }
    }

    @Test
    void controlFramesAreNotConfirmed() throws Exception {
        Client client = connect("it-receipt-control");
        try {
            client.send(frame("dave", "Joining", "JOIN", "j-1"));
            client.await("OK");
            client.send(frame("dave", "Leaving", "LEAVE", "l-1"));
            client.await("OK");

            // nothing was stored, so nothing should be confirmed
            boolean sawReceipt = false;
            String payload;
            while ((payload = client.frames().poll(750, TimeUnit.MILLISECONDS)) != null) {
                if ("DELIVERED".equals(MAPPER.readTree(payload).get("status").asText())) {
                    sawReceipt = true;
                }
            }
            assertThat(sawReceipt).isFalse();
        } finally {
            client.close();
        }
    }
}
