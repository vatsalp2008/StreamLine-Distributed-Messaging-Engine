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

    public TokenAuthenticator(StreamlineProperties properties) {
        this.settings = properties.getAuth();
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

        byte[] expected = settings.getToken().getBytes(StandardCharsets.UTF_8);
        byte[] supplied = presented.getBytes(StandardCharsets.UTF_8);

        return MessageDigest.isEqual(expected, supplied);
    }
}
