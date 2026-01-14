package server.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import server.model.ChatMessage;
import server.repository.MessageRepository;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class ChatService {

    private final MessageRepository messageRepository;

    public ChatService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Async
    public CompletableFuture<Void> saveMessage(ChatMessage message, String roomId) {
        try {
            message.setRoomId(roomId);
            messageRepository.save(message);
        } catch (Exception e) {
            System.err.println("Error saving message: " + e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    public List<ChatMessage> getRecentMessages(String roomId) {
        return messageRepository.findTop50ByRoomIdOrderByTimestampDesc(roomId);
    }
}
