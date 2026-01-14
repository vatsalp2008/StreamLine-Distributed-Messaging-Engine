package server.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

/**
 * Represents a chat message in the chat system
 */
@Entity
@Table(name = "messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Min(1)
    @Max(100000)
    private int userId; // valid range: 1-100000

    @NotNull
    @Size(min = 3, max = 20)
    @Pattern(regexp = "^[a-zA-Z0-9]+$")
    private String username; // Username(3-20 ALPH characters)

    @NotNull
    @Size(min = 1, max = 500)
    private String message;

    @NotNull
    private String timestamp;

    @NotNull
    @Pattern(regexp = "TEXT|JOIN|LEAVE")
    private String messageType; // Type of message: TEXT, JOIN, or LEAVE

    private String roomId;

    /**
     * @return Gives userId
     */
    public int getUserId() {
        return userId;
    }

    /**
     * @param userId, Set the userId
     */
    public void setUserId(int userId) {
        this.userId = userId;
    }

    /**
     * @return gives username
     */
    public String getUsername() {
        return username;
    }

    /**
     * @param username, Set the userId
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * @return message, gives Message
     */
    public String getMessage() {
        return message;
    }

    /**
     * @param message, set the Message
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * @return timestamp, gives TimeStamp
     */
    public String getTimestamp() {
        return timestamp;
    }

    /**
     * @param timestamp, set the timestamp
     */
    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * @return messageType, gives messageType
     */
    public String getMessageType() {
        return messageType;
    }

    /**
     * @param messageType, set the message type(TEXT, JOIN, LEAVE)
     */
    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }
}
