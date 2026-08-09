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
     * Correlation id echoed back by the server, so a sender can tell which of
     * its own messages a reply answers. Null when the sender does not use one.
     */
    private final String clientId;

    /**
     * @param userId      -int, representing the user ID
     * @param username    -String, representing the username
     * @param message     -String, representing the chat msg
     * @param timestamp   -String, representing the timestamp
     * @param messageType -String, representing the type of msg ("TEXT", "JOIN", "LEAVE")
     */
    public ChatMessage(int userId, String username, String message,
                       String timestamp, String messageType) {
        this(userId, username, message, timestamp, messageType, null);
    }

    /**
     * @param clientId correlation id the server echoes back, or null for none
     */
    public ChatMessage(int userId, String username, String message,
                       String timestamp, String messageType, String clientId) {
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.timestamp = timestamp;
        this.messageType = messageType;
        this.clientId = clientId;
    }

    /**
     * @param clientId correlation id to attach
     * @return a copy tagged with the id, content untouched
     */
    public ChatMessage withClientId(String clientId) {
        return new ChatMessage(userId, username, message, timestamp, messageType, clientId);
    }

    public String getClientId() {
        return clientId;
    }

    /**
     * Returns a copy authored by the given identity.
     *
     * A connection joins once and is then held to that username, so a sender
     * stamps its own identity on generated content rather than sending whatever
     * author the generator invented.
     *
     * @param userId   -int, the sending connection's user id
     * @param username -String, the username the connection joined with
     * @return a copy with the author replaced, content untouched
     */
    public ChatMessage withAuthor(int userId, String username) {
        return new ChatMessage(userId, username, message, timestamp, messageType, clientId);
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
