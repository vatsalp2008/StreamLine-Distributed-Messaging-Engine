package server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
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
        return messageRepository.findTop50ByRoomIdOrderByTimestampDesc(roomId);
    }
}
