package server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.model.ChatMessage;
import server.repository.MessageRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private MessageRepository repository;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        repository = mock(MessageRepository.class);
        chatService = new ChatService(repository);
    }

    private ChatMessage message(String text, String type) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(42);
        msg.setUsername("alice");
        msg.setMessage(text);
        msg.setTimestamp("2026-08-04T10:00:00Z");
        msg.setMessageType(type);
        return msg;
    }

    @Test
    void saveMessageStampsRoomIdBeforePersisting() {
        ChatMessage msg = message("hello", "TEXT");

        chatService.saveMessage(msg, "room-7").join();

        assertThat(msg.getRoomId()).isEqualTo("room-7");
        verify(repository).save(msg);
    }

    @Test
    void saveMessageCompletesEvenWhenRepositoryFails() {
        when(repository.save(any(ChatMessage.class)))
                .thenThrow(new RuntimeException("database is down"));

        // persistence is fire-and-forget: a failed write must not surface to the caller
        assertThat(chatService.saveMessage(message("hi", "TEXT"), "room-1"))
                .isCompletedWithValue(null);
    }

    @Test
    void getRecentMessagesReturnsRepositoryHistory() {
        List<ChatMessage> history = List.of(message("newest", "TEXT"), message("older", "TEXT"));
        when(repository.findTop50ByRoomIdOrderByTimestampDesc("room-3")).thenReturn(history);

        assertThat(chatService.getRecentMessages("room-3")).isEqualTo(history);
    }

    @Test
    void getRecentMessagesReturnsEmptyListForUnknownRoom() {
        when(repository.findTop50ByRoomIdOrderByTimestampDesc(anyString())).thenReturn(List.of());

        assertThat(chatService.getRecentMessages("never-used")).isEmpty();
    }
}
