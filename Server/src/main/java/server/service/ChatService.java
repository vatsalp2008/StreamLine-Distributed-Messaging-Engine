package server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import server.configure.AsyncConfig;
import server.model.ChatMessage;
import server.repository.MessageRepository;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final MessageRepository messageRepository;

    /** Injectable so a test can assert the recorded edit time. */
    private final java.time.Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatService(MessageRepository messageRepository) {
        this(messageRepository, java.time.Clock.systemUTC());
    }

    ChatService(MessageRepository messageRepository, java.time.Clock clock) {
        this.messageRepository = messageRepository;
        this.clock = clock;
    }

    /**
     * Persists a message off the WebSocket thread.
     * Runs in its own transaction so a single bad row is rolled back on its own
     * instead of riding on whatever connection the caller happened to have.
     *
     * @param message -ChatMessage, Representing the message to store
     * @param roomId  -String, Representing the room the message belongs to
     * @return a future completing with the generated message id, or null when
     *         the write failed
     */
    @Async(AsyncConfig.PERSISTENCE_EXECUTOR)
    @Transactional
    public CompletableFuture<Long> saveMessage(ChatMessage message, String roomId) {
        try {
            message.setRoomId(roomId);
            // the generated id is what lets a caller refer to the stored row
            // afterwards, which a Void future threw away
            return CompletableFuture.completedFuture(messageRepository.save(message).getId());
        } catch (Exception e) {
            log.error("Failed to persist message for room {}: {}", roomId, e.getMessage(), e);
            // null id rather than a failed future: persistence is fire and
            // forget, and callers already treat a missing id as "not stored"
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * @param roomId -String, Representing the room to replay
     * @return the 50 most recent messages, newest first
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> getRecentMessages(String roomId) {
        return messageRepository.findTop50ByRoomIdOrderByTimestampDescIdDesc(roomId);
    }

    /**
     * @param roomId   -String, Representing the room to read
     * @param pageable -Pageable, page number and size
     * @return one page of the room's history, newest first
     */
    @Transactional(readOnly = true)
    public Page<ChatMessage> getMessagePage(String roomId, Pageable pageable) {
        return messageRepository.findByRoomIdOrderByTimestampDescIdDesc(roomId, pageable);
    }

    /**
     * Searches a room's history.
     *
     * @param roomId   -String, room to search
     * @param text     -String, substring to match, case-insensitive
     * @param username -String, restrict to one author, or null for everyone
     * @param pageable -Pageable, page number and size
     * @return matching messages, newest first
     */
    @Transactional(readOnly = true)
    public Page<ChatMessage> searchMessages(String roomId, String text, String username,
            Pageable pageable) {

        if (username == null || username.isBlank()) {
            return messageRepository
                    .findByRoomIdAndMessageContainingIgnoreCaseOrderByTimestampDescIdDesc(
                            roomId, text, pageable);
        }

        return messageRepository
                .findByRoomIdAndUsernameIgnoreCaseAndMessageContainingIgnoreCaseOrderByTimestampDescIdDesc(
                        roomId, username, text, pageable);
    }

    /**
     * Replaces the text of one message in a room.
     *
     * Scoped by room for the same reason deletion is: a token for one room must
     * not reach another room's messages by guessing ids.
     *
     * @param roomId    -String, the room the message must belong to
     * @param messageId -Long, the stored id, as reported by a delivery receipt
     * @param text      -String, the replacement body
     * @return the updated message, or empty when it does not exist in that room
     */
    @Transactional
    public java.util.Optional<ChatMessage> editMessage(String roomId, Long messageId,
            String text) {

        return messageRepository.findById(messageId)
                .filter(message -> roomId.equals(message.getRoomId()))
                .map(message -> {
                    message.setMessage(text);
                    // the timestamp is when the author wrote it, so it stays put;
                    // rewriting it would reorder the room's history around an edit
                    message.setEditedAt(clock.instant());
                    return messageRepository.save(message);
                });
    }

    /**
     * Deletes one message from a room.
     *
     * @param roomId    -String, the room the message must belong to
     * @param messageId -Long, the stored id, as reported by a delivery receipt
     * @return true when a message was removed
     */
    @Transactional
    public boolean deleteMessage(String roomId, Long messageId) {
        return messageRepository.deleteFromRoom(messageId, roomId) > 0;
    }

    /**
     * @param roomId -String, Representing the room to count
     * @return how many messages the room has stored
     */
    @Transactional(readOnly = true)
    public long countMessages(String roomId) {
        return messageRepository.countByRoomId(roomId);
    }
}
