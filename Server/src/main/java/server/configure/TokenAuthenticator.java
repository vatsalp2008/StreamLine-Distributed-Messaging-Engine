package server.configure;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Checks a presented secret against the configured token.
 *
 * The comparison is constant time. A plain String.equals returns as soon as it
 * finds a differing character, so the time it takes leaks how much of the token
 * a guess got right, which is enough to recover a secret one character at a
 * time. MessageDigest.isEqual always inspects every byte.
 */
@Component
public class TokenAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthenticator.class);

    /** Shortest token accepted; anything less is trivially brute forced. */
    static final int MIN_TOKEN_LENGTH = 16;

    private final StreamlineProperties.Auth settings;

    /** Reloadable room secrets; null when the authenticator is built directly. */
    private final RoomTokenStore roomTokens;

    @org.springframework.beans.factory.annotation.Autowired
    public TokenAuthenticator(StreamlineProperties properties, RoomTokenStore roomTokens) {
        this.settings = properties.getAuth();
        this.roomTokens = roomTokens;
    }

    /** Uses only the statically configured tokens; convenient for tests. */
    public TokenAuthenticator(StreamlineProperties properties) {
        this(properties, null);
    }

    /**
     * @return the secret guarding a room, preferring a reloaded one
     */
    private String roomToken(String roomId) {
        if (roomId == null) {
            return null;
        }
        return roomTokens != null ? roomTokens.tokenFor(roomId)
                : settings.getRoomTokens().get(roomId);
    }

    /**
     * Refuses to start with access control switched on but no usable secret.
     *
     * Failing here is deliberate: the alternative is a server that believes it
     * is protected while accepting every request.
     */
    @PostConstruct
    void validateConfiguration() {
        if (!settings.isEnabled()) {
            log.info("Access control is disabled; every connection is accepted");
            return;
        }

        String token = settings.getToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "streamline.auth.enabled is true but streamline.auth.token is empty; "
                            + "set AUTH_TOKEN or disable access control");
        }
        if (token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException(
                    "streamline.auth.token must be at least " + MIN_TOKEN_LENGTH
                            + " characters, got " + token.length());
        }

        log.info("Access control is enabled; a token is required on /api and /chat");
    }

    public boolean isEnabled() {
        return settings.isEnabled();
    }

    public String headerName() {
        return settings.getHeader();
    }

    public String queryParamName() {
        return settings.getQueryParam();
    }

    public boolean protectsActuator() {
        return settings.isProtectActuator();
    }

    /**
     * Checks a secret against the token guarding a particular room.
     *
     * A room with its own token accepts only that one; anything else falls back
     * to the shared token, so adding a room secret does not require reissuing
     * credentials for every other room.
     *
     * @param roomId    the room being entered, may be null for non-room access
     * @param presented the secret supplied by the caller, may be null
     * @return true when access should be granted
     */
    public boolean isAuthorisedForRoom(String roomId, String presented) {
        if (!settings.isEnabled()) {
            return true;
        }

        String roomToken = roomToken(roomId);
        if (roomToken == null || roomToken.isBlank()) {
            return isAuthorised(presented);
        }
        return matches(roomToken, presented);
    }

    /**
     * @return true when this room has a secret of its own
     */
    public boolean hasRoomToken(String roomId) {
        String roomToken = roomToken(roomId);
        return roomToken != null && !roomToken.isBlank();
    }

    /**
     * @param presented the secret supplied by the caller, may be null
     * @return true when access should be granted
     */
    public boolean isAuthorised(String presented) {
        if (!settings.isEnabled()) {
            return true;
        }
        if (presented == null || presented.isEmpty()) {
            return false;
        }

        return matches(settings.getToken(), presented);
    }

    /** Constant-time comparison, for the same reason as {@link #isAuthorised}. */
    private boolean matches(String expected, String presented) {
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
