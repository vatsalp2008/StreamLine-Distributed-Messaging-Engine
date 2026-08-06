package server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the REST API against a running server, over real HTTP.
 *
 * The controller unit tests drive a mocked service through MockMvc; this checks
 * the pieces that only exist in a full application: JSON serialisation of an
 * Instant, the correlation id filter, and the API reporting rooms that were
 * populated over an actual WebSocket connection.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:streamline-api-it;DB_CLOSE_DELAY=-1",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class ChatApiIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    /** Joins a room over a real socket and optionally says something. */
    private WebSocketSession join(String room, String username, String text) throws Exception {
        WebSocketSession session = new StandardWebSocketClient()
                .execute(new TextWebSocketHandler(), new WebSocketHttpHeaders(),
                        URI.create("ws://localhost:" + port + "/chat/" + room))
                .get(10, TimeUnit.SECONDS);

        session.sendMessage(new TextMessage(frame(username, "Joining", "JOIN")));
        if (text != null) {
            session.sendMessage(new TextMessage(frame(username, text, "TEXT")));
        }
        return session;
    }

    private String frame(String username, String text, String type) {
        return """
                {"userId":7,"username":"%s","message":"%s","timestamp":"%s","messageType":"%s"}
                """.formatted(username, text, Instant.now().toString(), type);
    }

    @Test
    void roomsListsARoomPopulatedOverWebSocket() throws Exception {
        String room = "it-" + UUID.randomUUID().toString().substring(0, 8);

        try (WebSocketSession session = join(room, "alice", null)) {
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                JsonNode rooms = rest.getForObject("/api/rooms", JsonNode.class);
                assertThat(rooms.toString()).contains(room);
            });
        }
    }

    @Test
    void roomDetailNamesThePeoplePresent() throws Exception {
        String room = "it-" + UUID.randomUUID().toString().substring(0, 8);

        try (WebSocketSession alice = join(room, "alice", null);
             WebSocketSession bob = join(room, "bob", null)) {

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                JsonNode detail = rest.getForObject("/api/rooms/" + room, JsonNode.class);
                assertThat(detail.get("roomId").asText()).isEqualTo(room);
                assertThat(detail.get("members").toString()).contains("alice").contains("bob");
                assertThat(detail.get("sessions").asInt()).isEqualTo(2);
            });
        }
    }

    @Test
    void historyIsReadableOverHttpAfterBeingSentOverWebSocket() throws Exception {
        String room = "it-" + UUID.randomUUID().toString().substring(0, 8);

        try (WebSocketSession session = join(room, "alice", "persisted hello")) {
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                JsonNode page = rest.getForObject(
                        "/api/rooms/" + room + "/messages", JsonNode.class);

                assertThat(page.get("totalMessages").asLong()).isEqualTo(1);
                JsonNode first = page.get("messages").get(0);
                assertThat(first.get("username").asText()).isEqualTo("alice");
                assertThat(first.get("message").asText()).isEqualTo("persisted hello");
                // the entity stores an Instant; it must serialise as ISO-8601 text
                assertThat(Instant.parse(first.get("timestamp").asText())).isNotNull();
            });
        }
    }

    @Test
    void anUnknownRoomReturnsAnEmptyPage() {
        JsonNode page = rest.getForObject("/api/rooms/never-used/messages", JsonNode.class);

        assertThat(page.get("totalMessages").asLong()).isZero();
        assertThat(page.get("messages")).isEmpty();
        assertThat(page.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void aNegativePageIsRejectedWithAStructuredError() {
        ResponseEntity<JsonNode> response = rest.getForEntity(
                "/api/rooms/general/messages?page=-1", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("status").asInt()).isEqualTo(400);
        assertThat(response.getBody().get("message").asText()).contains("must not be negative");
    }

    @Test
    void everyResponseCarriesACorrelationId() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/rooms", JsonNode.class);

        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void aSuppliedCorrelationIdIsEchoedBack() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Correlation-Id", "trace-from-caller");

        ResponseEntity<JsonNode> response = rest.exchange("/api/rooms",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo("trace-from-caller");
    }

    @Test
    void theOpenApiDocumentDescribesTheApi() {
        JsonNode doc = rest.getForObject("/v3/api-docs", JsonNode.class);

        assertThat(doc.get("info").get("title").asText()).isEqualTo("StreamLine API");
        assertThat(doc.get("paths").has("/api/rooms")).isTrue();
        assertThat(doc.get("paths").has("/api/rooms/{roomId}/messages")).isTrue();
    }

    @Test
    void actuatorReportsMetricsButNotInternals() {
        assertThat(rest.getForEntity("/actuator/health", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // env would expose configuration, so it must stay unexposed
        assertThat(rest.getForEntity("/actuator/env", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
