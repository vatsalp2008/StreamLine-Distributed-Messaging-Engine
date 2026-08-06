package server.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageTest {

    private ChatMessage message(Long id, String username, String text) {
        ChatMessage msg = new ChatMessage();
        msg.setId(id);
        msg.setUserId(7);
        msg.setUsername(username);
        msg.setMessage(text);
        msg.setMessageType("TEXT");
        msg.setRoomId("general");
        msg.setTimestamp(Instant.parse("2026-08-06T10:00:00Z"));
        return msg;
    }

    @Test
    void rowsWithTheSameIdAreEqual() {
        assertThat(message(1L, "alice", "hello")).isEqualTo(message(1L, "bob", "different"));
    }

    @Test
    void rowsWithDifferentIdsAreNotEqual() {
        assertThat(message(1L, "alice", "hello")).isNotEqualTo(message(2L, "alice", "hello"));
    }

    @Test
    void unsavedMessagesAreOnlyEqualToThemselves() {
        ChatMessage unsaved = message(null, "alice", "hello");

        assertThat(unsaved).isEqualTo(unsaved);
        assertThat(unsaved).isNotEqualTo(message(null, "alice", "hello"));
    }

    @Test
    void distinctUnsavedMessagesAllSurviveInASet() {
        Set<ChatMessage> set = new HashSet<>();
        set.add(message(null, "alice", "one"));
        set.add(message(null, "alice", "two"));

        // if unsaved entities compared equal they would collapse into one
        assertThat(set).hasSize(2);
    }

    @Test
    void hashCodeSurvivesBeingAssignedAnId() {
        ChatMessage msg = message(null, "alice", "hello");
        int beforePersist = msg.hashCode();

        msg.setId(99L);

        // a set built before the insert can still find the entity afterwards
        assertThat(msg.hashCode()).isEqualTo(beforePersist);
    }

    @Test
    void toStringDescribesTheMessageWithoutLeakingItsBody() {
        String text = message(5L, "alice", "my secret plans").toString();

        assertThat(text).contains("id=5", "alice", "TEXT", "general", "messageLength=15");
        assertThat(text).doesNotContain("my secret plans");
    }

    @Test
    void toStringHandlesAnEmptyMessage() {
        ChatMessage msg = message(1L, "alice", null);

        assertThat(msg.toString()).contains("messageLength=0");
    }

    @Test
    void aMessageIsNotEqualToOtherTypes() {
        assertThat(message(1L, "alice", "hello")).isNotEqualTo("not a message");
    }
}
