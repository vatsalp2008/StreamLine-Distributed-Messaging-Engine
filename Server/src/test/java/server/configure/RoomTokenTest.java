package server.configure;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Room-scoped secrets.
 *
 * With only a shared token, anyone holding it can enter every room. A room
 * listed with its own token accepts that one alone.
 */
class RoomTokenTest {

    private static final String SHARED = "shared-token-long-enough";
    private static final String PRIVATE = "private-room-token-value";

    private StreamlineProperties properties(Map<String, String> roomTokens) {
        StreamlineProperties properties = new StreamlineProperties();
        properties.getAuth().setEnabled(true);
        properties.getAuth().setToken(SHARED);
        properties.getAuth().setRoomTokens(new HashMap<>(roomTokens));
        return properties;
    }

    private TokenAuthenticator authenticator(Map<String, String> roomTokens) {
        return new TokenAuthenticator(properties(roomTokens));
    }

    /** Runs the handshake check for a room and reports whether it was allowed. */
    private boolean handshake(StreamlineProperties properties, String room, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat/" + room);
        request.setRequestURI("/chat/" + room);
        if (token != null) {
            request.setParameter("token", token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = new TokenHandshakeInterceptor(new TokenAuthenticator(properties))
                .beforeHandshake(new ServletServerHttpRequest(request),
                        new ServletServerHttpResponse(response),
                        mock(WebSocketHandler.class), new HashMap<>());

        if (!allowed) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }
        return allowed;
    }

    // ---------- authenticator ----------

    @Test
    void aRoomWithoutItsOwnTokenAcceptsTheSharedOne() {
        TokenAuthenticator auth = authenticator(Map.of("private", PRIVATE));

        assertThat(auth.isAuthorisedForRoom("general", SHARED)).isTrue();
    }

    @Test
    void aRoomWithItsOwnTokenAcceptsIt() {
        TokenAuthenticator auth = authenticator(Map.of("private", PRIVATE));

        assertThat(auth.isAuthorisedForRoom("private", PRIVATE)).isTrue();
    }

    @Test
    void theSharedTokenDoesNotOpenARoomWithItsOwnSecret() {
        TokenAuthenticator auth = authenticator(Map.of("private", PRIVATE));

        // this is the whole point: holding the shared token is not enough
        assertThat(auth.isAuthorisedForRoom("private", SHARED)).isFalse();
    }

    @Test
    void aRoomTokenDoesNotOpenOtherRooms() {
        TokenAuthenticator auth = authenticator(Map.of("private", PRIVATE));

        assertThat(auth.isAuthorisedForRoom("general", PRIVATE)).isFalse();
    }

    @Test
    void oneRoomTokenDoesNotOpenAnotherRoomsSecret() {
        TokenAuthenticator auth = authenticator(Map.of(
                "alpha", "alpha-token-long-enough",
                "beta", "beta-token-long-enough2"));

        assertThat(auth.isAuthorisedForRoom("beta", "alpha-token-long-enough")).isFalse();
        assertThat(auth.isAuthorisedForRoom("beta", "beta-token-long-enough2")).isTrue();
    }

    @Test
    void aBlankRoomTokenFallsBackToTheSharedOne() {
        // an empty entry should not lock a room out entirely
        TokenAuthenticator auth = authenticator(Map.of("half-configured", "  "));

        assertThat(auth.isAuthorisedForRoom("half-configured", SHARED)).isTrue();
    }

    @Test
    void everythingIsOpenWhenAuthIsDisabled() {
        StreamlineProperties properties = properties(Map.of("private", PRIVATE));
        properties.getAuth().setEnabled(false);

        assertThat(new TokenAuthenticator(properties).isAuthorisedForRoom("private", null))
                .isTrue();
    }

    @Test
    void roomsWithTheirOwnSecretAreIdentifiable() {
        TokenAuthenticator auth = authenticator(Map.of("private", PRIVATE));

        assertThat(auth.hasRoomToken("private")).isTrue();
        assertThat(auth.hasRoomToken("general")).isFalse();
        assertThat(auth.hasRoomToken(null)).isFalse();
    }

    // ---------- handshake ----------

    @Test
    void aHandshakeIntoAPrivateRoomNeedsItsOwnToken() {
        StreamlineProperties properties = properties(Map.of("private", PRIVATE));

        assertThat(handshake(properties, "private", PRIVATE)).isTrue();
        assertThat(handshake(properties, "private", SHARED)).isFalse();
    }

    @Test
    void aHandshakeIntoAnOrdinaryRoomStillUsesTheSharedToken() {
        StreamlineProperties properties = properties(Map.of("private", PRIVATE));

        assertThat(handshake(properties, "general", SHARED)).isTrue();
        assertThat(handshake(properties, "general", "wrong-token-entirely")).isFalse();
    }

    @Test
    void aNestedPathStillResolvesTheRoom() {
        StreamlineProperties properties = properties(Map.of("private", PRIVATE));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat/private/extra");
        request.setRequestURI("/chat/private/extra");
        request.setParameter("token", SHARED);

        // the room is the first segment, so the shared token must still be refused
        boolean allowed = new TokenHandshakeInterceptor(new TokenAuthenticator(properties))
                .beforeHandshake(new ServletServerHttpRequest(request),
                        new ServletServerHttpResponse(new MockHttpServletResponse()),
                        mock(WebSocketHandler.class), new HashMap<>());

        assertThat(allowed).isFalse();
    }
}
