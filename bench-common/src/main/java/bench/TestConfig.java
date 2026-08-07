package bench;

/**
 * Resolves benchmark settings without recompiling.
 *
 * Lookup order for each setting: JVM system property (-Dstreamline.url=...),
 * then environment variable (STREAMLINE_URL), then the caller's default.
 */
public final class TestConfig {

    private TestConfig() {
    }

    /**
     * @return -String, Representing the WebSocket base URL of the server under test
     */
    public static String serverUrl() {
        return string("streamline.url", "STREAMLINE_URL", "ws://localhost:8080");
    }

    /**
     * @param fallback -int, value to use when nothing is configured
     * @return -int, Representing the number of sender threads
     */
    public static int threads(int fallback) {
        return positiveInt("streamline.threads", "STREAMLINE_THREADS", fallback);
    }

    /**
     * @param fallback -int, value to use when nothing is configured
     * @return -int, Representing the total number of messages to send
     */
    public static int totalMessages(int fallback) {
        return positiveInt("streamline.messages", "STREAMLINE_MESSAGES", fallback);
    }

    /**
     * @param fallback -int, value to use when nothing is configured
     * @return -int, Representing how many rooms load is spread across
     */
    public static int rooms(int fallback) {
        return positiveInt("streamline.rooms", "STREAMLINE_ROOMS", fallback);
    }

    /**
     * @return -String, Representing the directory the CSV reports are written to
     */
    public static String resultDir() {
        return string("streamline.result.dir", "STREAMLINE_RESULT_DIR", "Result");
    }

    /**
     * @return -String, the shared secret to present, or empty when the server
     *         under test has access control switched off
     */
    public static String authToken() {
        return string("streamline.token", "STREAMLINE_TOKEN", "");
    }

    /**
     * Appends the token to a chat URL when one is configured.
     *
     * The token goes in the query string rather than a header because that is
     * the form the server accepts on a handshake from any client.
     *
     * @param chatUrl -String, the ws://host/chat/{room} URL
     * @return the URL a client should actually connect to
     */
    public static String withAuth(String chatUrl) {
        String token = authToken();
        if (token.isEmpty()) {
            return chatUrl;
        }

        String separator = chatUrl.contains("?") ? "&" : "?";
        return chatUrl + separator + "token="
                + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String string(String property, String envVar, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(envVar);
        }
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static int positiveInt(String property, String envVar, int fallback) {
        String raw = string(property, envVar, null);
        if (raw == null) {
            return fallback;
        }

        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                System.err.println(property + " must be positive, using default " + fallback);
                return fallback;
            }
            return value;
        } catch (NumberFormatException e) {
            System.err.println("Ignoring non-numeric " + property + "=" + raw + ", using " + fallback);
            return fallback;
        }
    }
}
