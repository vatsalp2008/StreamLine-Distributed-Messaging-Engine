package server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import server.model.ChatMessage;
import server.repository.MessageRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rewriting a stored message.
 *
 * Against a real database, because the room scoping is a security property and
 * because keeping the original timestamp is what stops an edit from reordering
 * a room's history.
 */
@DataJpaTest
@Import(ChatService.class)
class MessageEditTest {

    private static final Instant EARLIER = Instant.parse("2026-08-12T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-12T11:00:00Z");

    @Autowired
    private MessageRepository repository;

    @Autowired
    private ChatService chatService;

    private Long generalId;
    private Long privateId;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        generalId = store("general", "original text", EARLIER);
        privateId = store("private", "other room", EARLIER);
    }

    private Long store(String room, String text, Instant when) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(1);
        msg.setUsername("alice");
        msg.setMessage(text);
        msg.setMessageType("TEXT");
        msg.setRoomId(room);
        msg.setTimestamp(when);
        return repository.save(msg).getId();
    }

    @Test
    void theTextIsReplaced() {
        chatService.editMessage("general", generalId, "corrected text");

        assertThat(repository.findById(generalId))
                .get()
                .extracting(ChatMessage::getMessage)
                .isEqualTo("corrected text");
    }

    @Test
    void theUpdatedMessageIsReturned() {
        assertThat(chatService.editMessage("general", generalId, "corrected"))
                .get()
                .extracting(ChatMessage::getMessage)
                .isEqualTo("corrected");
    }

    @Test
    void theOriginalTimestampIsKept() {
        chatService.editMessage("general", generalId, "corrected");

        // rewriting it would move the message within the room's history
        assertThat(repository.findById(generalId))
                .get()
                .extracting(ChatMessage::getTimestamp)
                .isEqualTo(EARLIER);
    }

    @Test
    void anEditDoesNotReorderHistory() {
        store("general", "newer message", LATER);

        chatService.editMessage("general", generalId, "corrected");

        List<ChatMessage> history = chatService.getRecentMessages("general");
        assertThat(history).extracting(ChatMessage::getMessage)
                .containsExactly("newer message", "corrected");
    }

    @Test
    void theAuthorIsUnchanged() {
        chatService.editMessage("general", generalId, "corrected");

        // an edit changes what was said, not who said it
        assertThat(repository.findById(generalId))
                .get()
                .extracting(ChatMessage::getUsername)
                .isEqualTo("alice");
    }

    @Test
    void aMessageInAnotherRoomIsNotTouched() {
        assertThat(chatService.editMessage("general", privateId, "hijacked")).isEmpty();

        assertThat(repository.findById(privateId))
                .get()
                .extracting(ChatMessage::getMessage)
                .isEqualTo("other room");
    }

    @Test
    void anUnknownIdIsReported() {
        assertThat(chatService.editMessage("general", 999_999L, "nothing")).isEmpty();
    }

    @Test
    void anEditedMessageIsFoundBySearchOnItsNewText() {
        chatService.editMessage("general", generalId, "completely different wording");

        assertThat(chatService.searchMessages("general", "different", null,
                PageRequest.of(0, 50)).getTotalElements()).isEqualTo(1);
    }

    @Test
    void anEditedMessageIsNoLongerFoundByItsOldText() {
        chatService.editMessage("general", generalId, "completely different wording");

        assertThat(chatService.searchMessages("general", "original", null,
                PageRequest.of(0, 50)).getTotalElements()).isZero();
    }

    @Test
    void aDeletedMessageCannotBeEdited() {
        chatService.deleteMessage("general", generalId);

        assertThat(chatService.editMessage("general", generalId, "back from the dead")).isEmpty();
    }

    @Test
    void editingDoesNotChangeHowManyMessagesTheRoomHas() {
        chatService.editMessage("general", generalId, "corrected");

        assertThat(chatService.countMessages("general")).isEqualTo(1);
    }
}
