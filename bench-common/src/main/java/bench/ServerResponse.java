package bench;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

/**
 * Classifies a frame the server sent back.
 *
 * The clients used to treat any reply as a success, because the only question
 * asked was "did something come back". That silently counts refusals as
 * throughput: a run where the server rejected every message still reported a
 * hundred percent success rate.
 */
public final class ServerResponse {

    private static final Gson GSON = new Gson();

    private ServerResponse() {
    }

    /**
     * @param payload the raw frame received from the server
     * @return true when the frame reports that the message was acted on
     */
    public static boolean isAccepted(String payload) {
        String status = statusOf(payload);
        // an unparseable frame is not evidence of success
        return status != null && !"ERROR".equals(status);
    }

    /**
     * Whether a frame is the server's answer to the message this client just sent.
     *
     * BROADCAST, HISTORY and PRESENCE are unsolicited pushes: they arrive because
     * of what other clients did. Treating them as an acknowledgement let a sender
     * be released by someone else's traffic and count a success before its own
     * message had even been processed, which inflated throughput whenever fan-out
     * was enabled.
     *
     * @param payload the raw frame received from the server
     * @return true when the frame answers this client's own message
     */
    public static boolean isDirectReply(String payload) {
        String status = statusOf(payload);
        return "OK".equals(status) || "ERROR".equals(status);
    }

    /**
     * @param payload the raw frame received from the server
     * @return the status field, or null when the frame is not a status frame
     */
    public static String statusOf(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }

        try {
            JsonObject json = GSON.fromJson(payload, JsonObject.class);
            if (json == null || !json.has("status")) {
                return null;
            }
            return json.get("status").getAsString();
        } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
            // malformed or unexpected shape; the caller treats this as not accepted
            return null;
        }
    }
}
