package server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import server.configure.StreamlineProperties;
import server.model.ChatMessage;
import server.repository.MessageRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention against a real database.
 *
 * The delete is a JPQL bulk statement, so only running it shows the query parses
 * and removes what it should. The clock is injected rather than slept on.
 */
@DataJpaTest
class RetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");

    @Autowired
    private MessageRepository repository;

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    private RetentionService serviceKeeping(int days) {
        StreamlineProperties properties = new StreamlineProperties();
        properties.getRetention().setDays(days);
        return new RetentionService(repository, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void store(String text, Instant when) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(1);
        msg.setUsername("alice");
        msg.setMessage(text);
        msg.setMessageType("TEXT");
        msg.setRoomId("general");
        msg.setTimestamp(when);
        repository.save(msg);
    }

    @Test
    void messagesOlderThanTheWindowAreRemoved() {
        store("ancient", NOW.minusSeconds(40 * 86400));
        store("recent", NOW.minusSeconds(86400));

        int removed = serviceKeeping(30).prune();

        assertThat(removed).isEqualTo(1);
        assertThat(repository.findAll()).extracting(ChatMessage::getMessage)
                .containsExactly("recent");
    }

    @Test
    void messagesInsideTheWindowAreKept() {
        store("yesterday", NOW.minusSeconds(86400));
        store("last week", NOW.minusSeconds(7 * 86400));

        assertThat(serviceKeeping(30).prune()).isZero();
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void aZeroWindowKeepsEverything() {
        store("very old", NOW.minusSeconds(3650 * 86400));

        // the default must not delete history from an existing deployment
        assertThat(serviceKeeping(0).prune()).isZero();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void aNegativeWindowKeepsEverything() {
        store("very old", NOW.minusSeconds(3650 * 86400));

        assertThat(serviceKeeping(-5).prune()).isZero();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void aMessageExactlyAtTheBoundaryIsKept() {
        // the cutoff is exclusive, so a message precisely at the edge survives
        store("boundary", NOW.minusSeconds(30 * 86400));

        assertThat(serviceKeeping(30).prune()).isZero();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void pruningAnEmptyTableIsHarmless() {
        assertThat(serviceKeeping(30).prune()).isZero();
    }

    @Test
    void pruningIsIdempotent() {
        store("ancient", NOW.minusSeconds(40 * 86400));
        RetentionService service = serviceKeeping(30);

        assertThat(service.prune()).isEqualTo(1);
        assertThat(service.prune()).isZero();
    }

    @Test
    void retentionSpansEveryRoom() {
        store("old general", NOW.minusSeconds(40 * 86400));

        ChatMessage other = new ChatMessage();
        other.setUserId(1);
        other.setUsername("bob");
        other.setMessage("old other");
        other.setMessageType("TEXT");
        other.setRoomId("other");
        other.setTimestamp(NOW.minusSeconds(40 * 86400));
        repository.save(other);

        assertThat(serviceKeeping(30).prune()).isEqualTo(2);
        assertThat(repository.count()).isZero();
    }
}
