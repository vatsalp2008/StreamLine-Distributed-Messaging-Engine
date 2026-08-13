package server.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import server.model.ChatMessage;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migrations and repository queries against a real Postgres.
 *
 * Everything else in the suite runs on H2, which generates different DDL and
 * accepts SQL that Postgres rejects, so a green suite said nothing about the
 * database this deploys against. This runs the same Flyway migrations and the
 * same derived queries there.
 *
 * Points at a Postgres supplied through POSTGRES_TEST_URL and is skipped when
 * that is unset, so a machine without one still gets a clean build. Deliberately
 * not Testcontainers: that pulls in a Docker client which has to negotiate an
 * API version with whatever daemon is installed, and this suite should not fail
 * because of a version mismatch unrelated to the code.
 *
 *   make test-postgres     # starts Postgres, runs this, tears it down
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class PostgresSchemaTest {

    @DynamicPropertySource
    static void postgresConnection(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("POSTGRES_TEST_URL"));
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("POSTGRES_TEST_USER", "streamline"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "streamline"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // the point is that the real migrations produce a schema the entities
        // validate against, exactly as in production
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.database-platform", () -> "");
    }

    @Autowired
    private MessageRepository repository;

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    private ChatMessage store(String room, String username, String text, Instant when) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(1);
        msg.setUsername(username);
        msg.setMessage(text);
        msg.setMessageType("TEXT");
        msg.setRoomId(room);
        msg.setTimestamp(when);
        return repository.save(msg);
    }

    @Test
    void theMigrationsProduceASchemaTheEntitiesValidateAgainst() {
        // reaching this point means Flyway ran and Hibernate's validate passed
        assertThat(repository.count()).isZero();
    }

    @Test
    void aMessageRoundTrips() {
        ChatMessage saved = store("general", "alice", "hello postgres",
                Instant.parse("2026-08-13T10:00:00Z"));

        assertThat(repository.findById(saved.getId()))
                .get()
                .extracting(ChatMessage::getMessage)
                .isEqualTo("hello postgres");
    }

    @Test
    void historyOrdersNewestFirst() {
        store("general", "alice", "older", Instant.parse("2026-08-13T10:00:00Z"));
        store("general", "bob", "newer", Instant.parse("2026-08-13T11:00:00Z"));

        assertThat(repository.findTop50ByRoomIdOrderByTimestampDescIdDesc("general"))
                .extracting(ChatMessage::getMessage)
                .containsExactly("newer", "older");
    }

    @Test
    void caseInsensitiveSearchWorksOnPostgres() {
        store("general", "alice", "Deploying the BUILD", Instant.parse("2026-08-13T10:00:00Z"));

        // ILIKE semantics differ from H2's; this is the query that matters
        assertThat(repository
                .findByRoomIdAndMessageContainingIgnoreCaseOrderByTimestampDescIdDesc(
                        "general", "build", PageRequest.of(0, 50))
                .getTotalElements()).isEqualTo(1);
    }

    @Test
    void theScopedDeleteReportsARowCount() {
        ChatMessage saved = store("general", "alice", "delete me",
                Instant.parse("2026-08-13T10:00:00Z"));

        assertThat(repository.deleteFromRoom(saved.getId(), "general")).isEqualTo(1);
        assertThat(repository.deleteFromRoom(saved.getId(), "general")).isZero();
    }

    @Test
    void aDeleteScopedToTheWrongRoomRemovesNothing() {
        ChatMessage saved = store("general", "alice", "stay",
                Instant.parse("2026-08-13T10:00:00Z"));

        assertThat(repository.deleteFromRoom(saved.getId(), "other")).isZero();
        assertThat(repository.findById(saved.getId())).isPresent();
    }

    @Test
    void theEditedColumnExistsAndRoundTrips() {
        ChatMessage saved = store("general", "alice", "original",
                Instant.parse("2026-08-13T10:00:00Z"));
        Instant editedAt = Instant.parse("2026-08-13T12:00:00Z");

        saved.setEditedAt(editedAt);
        repository.save(saved);

        assertThat(repository.findById(saved.getId()))
                .get()
                .extracting(ChatMessage::getEditedAt)
                .isEqualTo(editedAt);
    }

    @Test
    void retentionDeletesByAge() {
        store("general", "alice", "ancient", Instant.parse("2026-01-01T00:00:00Z"));
        store("general", "alice", "recent", Instant.parse("2026-08-13T10:00:00Z"));

        assertThat(repository.deleteOlderThan(Instant.parse("2026-06-01T00:00:00Z")))
                .isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
    }
}
