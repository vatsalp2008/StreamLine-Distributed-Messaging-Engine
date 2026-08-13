package server.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * One person's reaction to one message.
 *
 * Kept in its own table rather than on the message: reactions arrive and vanish
 * independently and there are many per message, so holding them inline would
 * rewrite the message row on every click.
 */
@Entity
@Table(name = "message_reactions", indexes = {
        @Index(name = "idx_reactions_message", columnList = "message_id"),
        @Index(name = "idx_reactions_room", columnList = "room_id")
}, uniqueConstraints = {
        // reacting twice the same way is one reaction, enforced here rather than
        // left to every caller to remember
        @UniqueConstraint(name = "uq_reaction_per_user",
                columnNames = {"message_id", "username", "emoji"})
})
public class MessageReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @NotNull
    @Column(name = "room_id", nullable = false, length = 64)
    private String roomId;

    @NotNull
    @Size(min = 3, max = 20)
    @Pattern(regexp = "^[a-zA-Z0-9]+$")
    @Column(nullable = false, length = 20)
    private String username;

    /**
     * The reaction itself. Short and length-bounded: this is a reaction, not a
     * second message, and an unbounded field here would be a way to store one.
     */
    @NotNull
    @Size(min = 1, max = 16)
    @Column(nullable = false, length = 16)
    private String emoji;

    @NotNull
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmoji() {
        return emoji;
    }

    public void setEmoji(String emoji) {
        this.emoji = emoji;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
