package bench.model;

import com.google.gson.Gson;

/**
 * A chat frame as the benchmark clients send it.
 *
 * Both clients previously carried their own near-identical copy of this class;
 * they now share this one so the wire format cannot drift between them.
 */
public class ChatMessage {

    /** Gson is thread safe and immutable once built, so one instance serves every message. */
    private static final Gson GSON = new Gson();

    private final int userId;
    private final String username;
    private final String message;
    private final String timestamp;
    private final String messageType;

    /**
     * @param userId      -int, representing the user ID
     * @param username    -String, representing the username
     * @param message     -String, representing the chat msg
     * @param timestamp   -String, representing the timestamp
     * @param messageType -String, representing the type of msg ("TEXT", "JOIN", "LEAVE")
     */
    public ChatMessage(int userId, String username, String message,
                       String timestamp, String messageType) {
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.timestamp = timestamp;
        this.messageType = messageType;
    }

    /**
     * @return this frame as the JSON the server expects
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    public int getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getMessage() {
        return message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    /** Needed when bucketing results by message type. */
    public String getMessageType() {
        return messageType;
    }
}
