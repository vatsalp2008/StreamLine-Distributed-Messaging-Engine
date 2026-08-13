package server.api;

import server.model.ChatMessage;

import java.time.Instant;

/**
 * A stored message as exposed over HTTP.
 *
 * Deliberately separate from the ChatMessage entity so the persistence model can
 * change without altering the public response, and so internal columns such as
 * the database id are not published.
 *
 * @param username  who sent it
 * @param message   the text
 * @param timestamp when the sender produced it
 * @param roomId    the room it belongs to
 * @param editedAt  when it was last rewritten, or null if never
 */
public record MessageView(String username, String message, Instant timestamp, String roomId,
        Instant editedAt) {

    public static MessageView from(ChatMessage entity) {
        return new MessageView(
                entity.getUsername(),
                entity.getMessage(),
                entity.getTimestamp(),
                entity.getRoomId(),
                entity.getEditedAt());
    }
}
