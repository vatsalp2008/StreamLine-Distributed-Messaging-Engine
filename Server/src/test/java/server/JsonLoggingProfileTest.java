package server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "json" profile switches console logging to structured output.
 *
 * Worth pinning down because the property name is the whole mechanism: a typo
 * leaves the server logging plain text while looking configured, and nothing
 * else would notice. The format itself is Spring Boot's, chosen over a
 * hand-written Logback pattern that produced invalid JSON whenever a message
 * spanned multiple lines.
 */
@SpringBootTest(
        // a real servlet container: the WebSocket container bean needs one
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        "spring.datasource.url=jdbc:h2:mem:streamline-jsonlog;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
        })
@ActiveProfiles("json")
class JsonLoggingProfileTest {

    @Autowired
    private Environment environment;

    @Test
    void theProfileSelectsStructuredConsoleLogging() {
        assertThat(environment.getProperty("logging.structured.format.console"))
                .isEqualTo("ecs");
    }

    @Test
    void theServiceNameIsTaggedOnEveryLine() {
        // without this every line is anonymous once shipped to a collector
        assertThat(environment.getProperty("logging.structured.ecs.service.name"))
                .isEqualTo("streamline");
    }

    @Test
    void theProfileIsActive() {
        assertThat(environment.getActiveProfiles()).contains("json");
    }
}
