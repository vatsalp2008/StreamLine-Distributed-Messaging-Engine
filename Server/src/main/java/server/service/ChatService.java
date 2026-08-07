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

    public ChatService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Persists a message off the WebSocket thread.
     * Runs in its own transaction so a single bad row is rolled back on its own
     * instead of riding on whatever connection the caller happened to have.
     *
     * @param message -ChatMessage, Representing the message to store
     * @param roomId  -String, Representing the room the message belongs to
     * @return a future that completes once the write attempt has finished
     */
    @Async(AsyncConfig.PERSISTENCE_EXECUTOR)
    @Transactional
    public CompletableFuture<Void> saveMessage(ChatMessage message, String roomId) {
        try {
            message.setRoomId(roomId);
            messageRepository.save(message);
        } catch (Exception e) {
            log.error("Failed to persist message for room {}: {}", roomId, e.getMessage(), e);
        }
        return CompletableFuture.completedFuture(null);
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
     * @param roomId -String, Representing the room to count
     * @return how many messages the room has stored
     */
    @Transactional(readOnly = true)
    public long countMessages(String roomId) {
        return messageRepository.countByRoomId(roomId);
    }
}
