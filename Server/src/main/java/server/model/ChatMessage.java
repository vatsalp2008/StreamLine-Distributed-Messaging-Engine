package server.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

import java.time.Instant;

/**
 * Represents a chat message in the chat system
 */
@Entity
@Table(name = "messages", indexes = {
        // history replay filters by room and orders by timestamp, so index both together
        @Index(name = "idx_messages_room_timestamp", columnList = "room_id, timestamp")
})
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
    @Column(nullable = false, length = 20)
    private String username; // Username(3-20 ALPH characters)

    @NotNull
    @Size(min = 1, max = 500)
    @Column(nullable = false, length = 500)
    private String message;

    /**
     * When the client produced the message.
     * Stored as an instant rather than text: string ordering only matches
     * chronological order for zero-padded UTC values, so a client sending an
     * offset such as +05:30 would sort into the wrong place in room history.
     */
    @NotNull
    @Column(nullable = false)
    private Instant timestamp;

    @NotNull
    @Pattern(regexp = "TEXT|JOIN|LEAVE")
    @Column(nullable = false, length = 10)
    private String messageType; // Type of message: TEXT, JOIN, or LEAVE

    @Column(name = "room_id", length = 64)
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
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * @param timestamp, set the timestamp
     */
    public void setTimestamp(Instant timestamp) {
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

    /**
     * Two messages are the same row only when both carry the same generated id.
     *
     * An unsaved entity has no id yet, so it is only equal to itself; otherwise
     * every new message would collide with every other new message in a set.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChatMessage that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    /**
     * Constant so that a message keeps the same hash after it is persisted and
     * gains an id, which a hash based on the id would not.
     */
    @Override
    public int hashCode() {
        return ChatMessage.class.hashCode();
    }

    /**
     * Message text is deliberately omitted: this shows up in logs, and the body
     * is user content that does not belong there.
     */
    @Override
    public String toString() {
        return "ChatMessage{id=" + id
                + ", username='" + username + '\''
                + ", messageType='" + messageType + '\''
                + ", roomId='" + roomId + '\''
                + ", timestamp=" + timestamp
                + ", messageLength=" + (message == null ? 0 : message.length())
                + '}';
    }
}
