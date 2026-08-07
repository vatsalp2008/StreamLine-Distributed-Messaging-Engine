package server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Access control against a running server, with auth switched on.
 *
 * The unit tests drive the filter and interceptor directly; this checks that
 * they are actually wired into the application, that an unauthenticated socket
 * is refused at the handshake, and that the probes stay reachable.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:streamline-auth-it;DB_CLOSE_DELAY=-1",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "streamline.auth.enabled=true",
                "streamline.auth.token=integration-test-token-value"
        })
class AuthIntegrationTest {

    private static final String TOKEN = "integration-test-token-value";

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    private ResponseEntity<JsonNode> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("X-Streamline-Token", token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private WebSocketSession openSocket(String query) throws Exception {
        return new StandardWebSocketClient()
                .execute(new TextWebSocketHandler(), new WebSocketHttpHeaders(),
                        URI.create("ws://localhost:" + port + "/chat/auth-room" + query))
                .get(10, TimeUnit.SECONDS);
    }

    // ---------- REST ----------

    @Test
    void theApiRejectsAnUnauthenticatedCall() {
        assertThat(get("/api/rooms", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theApiRejectsAWrongToken() {
        assertThat(get("/api/rooms", "wrong-token-entirely").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theApiAcceptsTheCorrectToken() {
        assertThat(get("/api/rooms", TOKEN).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theRejectionBodyIsTheStandardErrorShape() {
        ResponseEntity<JsonNode> response = get("/api/rooms", null);

        assertThat(response.getBody().get("status").asInt()).isEqualTo(401);
        assertThat(response.getBody().get("error").asText()).isEqualTo("Unauthorized");
    }

    @Test
    void historyIsAlsoProtected() {
        assertThat(get("/api/rooms/general/messages", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/rooms/general/messages", TOKEN).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---------- probes stay open ----------

    @Test
    void livenessAndReadinessRemainReachableWithoutAToken() {
        assertThat(get("/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/ready", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void actuatorIsProtected() {
        assertThat(get("/actuator/health", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/actuator/health", TOKEN).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------- WebSocket ----------

    @Test
    void anUnauthenticatedHandshakeIsRefused() {
        // the connection must fail outright, not connect and then misbehave
        assertThatThrownBy(() -> openSocket(""))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void aHandshakeWithTheWrongTokenIsRefused() {
        assertThatThrownBy(() -> openSocket("?token=nope"))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void aHandshakeWithTheTokenAsAQueryParameterSucceeds() throws Exception {
        // browsers cannot set handshake headers, so this path has to work
        try (WebSocketSession session = openSocket("?token=" + TOKEN)) {
            assertThat(session.isOpen()).isTrue();
        }
    }
}
