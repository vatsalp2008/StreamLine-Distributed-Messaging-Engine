package server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import server.model.ChatMessage;
import server.repository.MessageRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search against a real database.
 *
 * These derived queries are generated from their method names, so a mock proves
 * nothing: only running them shows the name parses and the SQL behaves.
 */
@DataJpaTest
@Import(ChatService.class)
class MessageSearchTest {

    @Autowired
    private MessageRepository repository;

    @Autowired
    private ChatService chatService;

    private Instant clock = Instant.parse("2026-08-07T10:00:00Z");

    @BeforeEach
    void seed() {
        repository.deleteAll();
        store("general", "alice", "Deploying the new build now");
        store("general", "bob", "the BUILD is green");
        store("general", "alice", "lunch?");
        store("other", "alice", "build something else entirely");
    }

    private void store(String room, String username, String text) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(1);
        msg.setUsername(username);
        msg.setMessage(text);
        msg.setMessageType("TEXT");
        msg.setRoomId(room);
        clock = clock.plusSeconds(1);
        msg.setTimestamp(clock);
        repository.save(msg);
    }

    private Page<ChatMessage> search(String room, String text, String username) {
        return chatService.searchMessages(room, text, username, PageRequest.of(0, 50));
    }

    @Test
    void findsMessagesContainingTheText() {
        Page<ChatMessage> results = search("general", "build", null);

        assertThat(results.getTotalElements()).isEqualTo(2);
    }

    @Test
    void matchingIsCaseInsensitive() {
        // "Deploying the new build" and "the BUILD is green" both match
        assertThat(search("general", "BUILD", null).getTotalElements()).isEqualTo(2);
        assertThat(search("general", "build", null).getTotalElements()).isEqualTo(2);
    }

    @Test
    void resultsAreScopedToTheRoom() {
        // the other room also contains "build" but must not appear
        assertThat(search("general", "build", null).getContent())
                .allSatisfy(msg -> assertThat(msg.getRoomId()).isEqualTo("general"));
    }

    @Test
    void resultsAreNewestFirst() {
        Page<ChatMessage> results = search("general", "build", null);

        assertThat(results.getContent().get(0).getMessage()).isEqualTo("the BUILD is green");
    }

    @Test
    void searchCanBeRestrictedToOneAuthor() {
        Page<ChatMessage> results = search("general", "build", "alice");

        assertThat(results.getTotalElements()).isEqualTo(1);
        assertThat(results.getContent().get(0).getUsername()).isEqualTo("alice");
    }

    @Test
    void theAuthorFilterIsCaseInsensitive() {
        assertThat(search("general", "build", "ALICE").getTotalElements()).isEqualTo(1);
    }

    @Test
    void aBlankAuthorMeansEveryone() {
        assertThat(search("general", "build", "  ").getTotalElements()).isEqualTo(2);
    }

    @Test
    void noMatchesReturnsAnEmptyPage() {
        Page<ChatMessage> results = search("general", "nothing matches this", null);

        assertThat(results.getTotalElements()).isZero();
        assertThat(results.getContent()).isEmpty();
    }

    @Test
    void anUnknownRoomReturnsNothing() {
        assertThat(search("never-used", "build", null).getTotalElements()).isZero();
    }

    @Test
    void resultsArePaged() {
        Page<ChatMessage> firstPage =
                chatService.searchMessages("general", "build", null, PageRequest.of(0, 1));

        assertThat(firstPage.getContent()).hasSize(1);
        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();
    }

    @Test
    void aSubstringInTheMiddleOfAWordMatches() {
        // LIKE %text% semantics, not word boundaries
        assertThat(search("general", "uild", null).getTotalElements()).isEqualTo(2);
    }
}
