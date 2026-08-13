package server.api;

import server.model.ChatMessage;

import java.time.Instant;

/**
 * A stored message as exposed over HTTP.
 *
 * Deliberately separate from the ChatMessage entity so the persistence model can
 * change without altering the public response.
 *
 * The id is published: the edit, delete and reaction endpoints all take one, so
 * without it an HTTP client could read a room's history and still have no way to
 * act on any of it. Over the socket a delivery receipt supplies the id, but
 * nothing supplied it here.
 *
 * @param id        the stored id, as taken by the moderation endpoints
 * @param username  who sent it
 * @param message   the text
 * @param timestamp when the sender produced it
 * @param roomId    the room it belongs to
 * @param editedAt  when it was last rewritten, or null if never
 */
public record MessageView(Long id, String username, String message, Instant timestamp,
        String roomId, Instant editedAt) {

    public static MessageView from(ChatMessage entity) {
        return new MessageView(
                entity.getId(),
                entity.getUsername(),
                entity.getMessage(),
                entity.getTimestamp(),
                entity.getRoomId(),
                entity.getEditedAt());
    }
}
