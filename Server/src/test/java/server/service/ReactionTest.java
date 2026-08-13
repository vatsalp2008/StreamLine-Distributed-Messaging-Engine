package server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import server.api.ReactionSummary;
import server.model.ChatMessage;
import server.repository.MessageRepository;
import server.repository.ReactionRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reacting to a message.
 *
 * Against a real database because the uniqueness rule is enforced there, and
 * because the room scoping is the same security property edit and delete rely on.
 */
@DataJpaTest
@Import(ChatService.class)
class ReactionTest {

    @Autowired
    private MessageRepository messages;

    @Autowired
    private ReactionRepository reactions;

    @Autowired
    private ChatService chatService;

    private Long generalId;
    private Long privateId;

    @BeforeEach
    void seed() {
        reactions.deleteAll();
        messages.deleteAll();
        generalId = store("general", "hello");
        privateId = store("private", "elsewhere");
    }

    private Long store(String room, String text) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(1);
        msg.setUsername("alice");
        msg.setMessage(text);
        msg.setMessageType("TEXT");
        msg.setRoomId(room);
        msg.setTimestamp(Instant.parse("2026-08-13T10:00:00Z"));
        return messages.save(msg).getId();
    }

    @Test
    void aReactionIsRecorded() {
        List<ReactionSummary> after = chatService
                .addReaction("general", generalId, "bob", "thumbsup").orElseThrow();

        assertThat(after).hasSize(1);
        assertThat(after.get(0).emoji()).isEqualTo("thumbsup");
        assertThat(after.get(0).count()).isEqualTo(1);
        assertThat(after.get(0).users()).containsExactly("bob");
    }

    @Test
    void severalPeopleCanUseTheSameReaction() {
        chatService.addReaction("general", generalId, "bob", "thumbsup");
        List<ReactionSummary> after = chatService
                .addReaction("general", generalId, "carol", "thumbsup").orElseThrow();

        assertThat(after).hasSize(1);
        assertThat(after.get(0).count()).isEqualTo(2);
        assertThat(after.get(0).users()).containsExactly("bob", "carol");
    }

    @Test
    void onePersonCanUseSeveralReactions() {
        chatService.addReaction("general", generalId, "bob", "thumbsup");
        List<ReactionSummary> after = chatService
                .addReaction("general", generalId, "bob", "heart").orElseThrow();

        assertThat(after).extracting(ReactionSummary::emoji)
                .containsExactly("thumbsup", "heart");
    }

    @Test
    void reactingTwiceTheSameWayChangesNothing() {
        chatService.addReaction("general", generalId, "bob", "thumbsup");

        // a double click is not a failure, and must not count twice
        List<ReactionSummary> after = chatService
                .addReaction("general", generalId, "bob", "thumbsup").orElseThrow();

        assertThat(after.get(0).count()).isEqualTo(1);
        assertThat(reactions.count()).isEqualTo(1);
    }

    @Test
    void aReactionCanBeTakenBack() {
        chatService.addReaction("general", generalId, "bob", "thumbsup");

        List<ReactionSummary> after = chatService
                .removeReaction("general", generalId, "bob", "thumbsup").orElseThrow();

        assertThat(after).isEmpty();
    }

    @Test
    void removingOnlyAffectsThePersonWhoReacted() {
        chatService.addReaction("general", generalId, "bob", "thumbsup");
        chatService.addReaction("general", generalId, "carol", "thumbsup");

        List<ReactionSummary> after = chatService
                .removeReaction("general", generalId, "bob", "thumbsup").orElseThrow();

        assertThat(after.get(0).users()).containsExactly("carol");
    }

    @Test
    void removingSomethingThatWasNeverThereIsReported() {
        assertThat(chatService.removeReaction("general", generalId, "bob", "thumbsup"))
                .isEmpty();
    }

    @Test
    void aMessageInAnotherRoomCannotBeReactedTo() {
        // the same scoping edit and delete rely on
        assertThat(chatService.addReaction("general", privateId, "bob", "thumbsup")).isEmpty();
        assertThat(reactions.count()).isZero();
    }

    @Test
    void anUnknownMessageCannotBeReactedTo() {
        assertThat(chatService.addReaction("general", 999_999L, "bob", "thumbsup")).isEmpty();
    }

    @Test
    void reactionsAreGroupedInFirstUsedOrder() {
        chatService.addReaction("general", generalId, "bob", "heart");
        chatService.addReaction("general", generalId, "carol", "thumbsup");
        chatService.addReaction("general", generalId, "dave", "heart");

        assertThat(chatService.reactionsFor(generalId))
                .extracting(ReactionSummary::emoji)
                .containsExactly("heart", "thumbsup");
    }

    @Test
    void aMessageWithNoReactionsReportsAnEmptyList() {
        assertThat(chatService.reactionsFor(generalId)).isEmpty();
    }

    @Test
    void deletingAMessageTakesItsReactionsWithIt() {
        chatService.addReaction("general", generalId, "bob", "thumbsup");

        chatService.deleteMessage("general", generalId);

        // the foreign key cascades, so reactions cannot outlive their message
        assertThat(reactions.count()).isZero();
    }
}
