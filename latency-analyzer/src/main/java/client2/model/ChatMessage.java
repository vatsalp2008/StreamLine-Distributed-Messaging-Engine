package client2.model;

import com.google.gson.Gson;

/**
 * class ChatMessage for model
 */
public class ChatMessage {
    private int userId;
    private String username;
    private String message;
    private String timestamp;
    private String messageType;

    /**
     * @param userId -int, representing the user ID
     * @param username -String, representing the username
     * @param message -String, representing the chat msg
     * @param timestamp -String, representing the timestamp
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
     * @return JSON strings
     */
    public String toJson() {
        return new Gson().toJson(this);
    }

    /**
     * @return give User ID
     */
    public int getUserId() { return userId; }

    /**
     * @return gives Message Types
     */
    public String getMessageType() { return messageType; }  // ← Need this for stats!
}