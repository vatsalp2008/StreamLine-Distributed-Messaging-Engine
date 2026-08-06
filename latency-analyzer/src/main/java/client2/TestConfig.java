package client2;

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
