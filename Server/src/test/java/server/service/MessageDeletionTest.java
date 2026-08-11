package server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import server.model.ChatMessage;
import server.repository.MessageRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting a single message.
 *
 * Run against a real database because the query is derived from its method
 * name, and because the room scoping is the security property being asserted.
 */
@DataJpaTest
@Import(ChatService.class)
class MessageDeletionTest {

    @Autowired
    private MessageRepository repository;

    @Autowired
    private ChatService chatService;

    private Long generalId;
    private Long privateId;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        generalId = store("general", "hello from general");
        privateId = store("private", "hello from private");
    }

    private Long store(String room, String text) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(1);
        msg.setUsername("alice");
        msg.setMessage(text);
        msg.setMessageType("TEXT");
        msg.setRoomId(room);
        msg.setTimestamp(Instant.parse("2026-08-11T10:00:00Z"));
        return repository.save(msg).getId();
    }

    @Test
    void aMessageIsRemovedFromItsRoom() {
        assertThat(chatService.deleteMessage("general", generalId)).isTrue();

        assertThat(repository.findById(generalId)).isEmpty();
    }

    @Test
    void deletingReportsWhetherAnythingWasRemoved() {
        assertThat(chatService.deleteMessage("general", generalId)).isTrue();
        // the same id a second time is no longer there
        assertThat(chatService.deleteMessage("general", generalId)).isFalse();
    }

    @Test
    void anIdFromAnotherRoomIsNotDeleted() {
        // the scoping is the point: one room's token must not reach another's
        assertThat(chatService.deleteMessage("general", privateId)).isFalse();

        assertThat(repository.findById(privateId)).isPresent();
    }

    @Test
    void anUnknownIdIsReportedRatherThanFailing() {
        assertThat(chatService.deleteMessage("general", 999_999L)).isFalse();
    }

    @Test
    void anUnknownRoomRemovesNothing() {
        assertThat(chatService.deleteMessage("never-used", generalId)).isFalse();

        assertThat(repository.findById(generalId)).isPresent();
    }

    @Test
    void otherMessagesInTheRoomSurvive() {
        Long second = store("general", "still here");

        chatService.deleteMessage("general", generalId);

        assertThat(repository.findById(second)).isPresent();
        assertThat(chatService.countMessages("general")).isEqualTo(1);
    }

    @Test
    void aDeletedMessageDisappearsFromHistory() {
        chatService.deleteMessage("general", generalId);

        assertThat(chatService.getRecentMessages("general")).isEmpty();
    }

    @Test
    void aDeletedMessageDisappearsFromSearch() {
        chatService.deleteMessage("general", generalId);

        assertThat(chatService.searchMessages("general", "hello", null,
                org.springframework.data.domain.PageRequest.of(0, 50)).getTotalElements())
                .isZero();
    }
}
