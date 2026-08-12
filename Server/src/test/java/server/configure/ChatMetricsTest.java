package server.configure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counters the README tells operators to alert on.
 *
 * Named metrics are a public interface: renaming one silently breaks every
 * dashboard and alert built on it, and nothing else in the suite would notice.
 */
class ChatMetricsTest {

    private SimpleMeterRegistry registry;
    private ChatMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ChatMetrics(registry);
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void everyDocumentedCounterExists() {
        for (String name : new String[] {
                "streamline.messages.accepted",
                "streamline.messages.rejected",
                "streamline.messages.rate_limited",
                "streamline.messages.identity_rejected",
                "streamline.messages.deleted",
                "streamline.messages.edited",
                "streamline.receipts.sent",
                "streamline.typing.sent",
                "streamline.broadcasts.sent"}) {
            assertThat(registry.find(name).counter())
                    .as("%s is documented, so it must be registered", name)
                    .isNotNull();
        }
    }

    @Test
    void everyCounterCarriesADescription() {
        registry.getMeters().forEach(meter ->
                assertThat(meter.getId().getDescription())
                        .as("%s should be self-describing in a scrape", meter.getId().getName())
                        .isNotBlank());
    }

    @Test
    void countersStartAtZero() {
        assertThat(count("streamline.messages.accepted")).isZero();
        assertThat(count("streamline.messages.deleted")).isZero();
    }

    @Test
    void eachRecordIncrementsItsOwnCounter() {
        metrics.recordAccepted();
        metrics.recordRejected();
        metrics.recordRateLimited();
        metrics.recordDeleted();
        metrics.recordEdited();
        metrics.recordReceipt();

        assertThat(count("streamline.messages.accepted")).isEqualTo(1);
        assertThat(count("streamline.messages.rejected")).isEqualTo(1);
        assertThat(count("streamline.messages.rate_limited")).isEqualTo(1);
        assertThat(count("streamline.messages.deleted")).isEqualTo(1);
        assertThat(count("streamline.messages.edited")).isEqualTo(1);
        assertThat(count("streamline.receipts.sent")).isEqualTo(1);
    }

    @Test
    void anIdentityRefusalCountsAsARejectionToo() {
        metrics.recordIdentityRejected();

        // the specific counter is a breakdown of the general one, so a
        // dashboard totalling rejections does not have to know about it
        assertThat(count("streamline.messages.identity_rejected")).isEqualTo(1);
        assertThat(count("streamline.messages.rejected")).isEqualTo(1);
    }

    @Test
    void oneRecordDoesNotDisturbAnother() {
        metrics.recordDeleted();
        metrics.recordDeleted();

        // moderation and expiry are counted separately on purpose
        assertThat(count("streamline.messages.deleted")).isEqualTo(2);
        assertThat(count("streamline.messages.edited")).isZero();
    }

    @Test
    void fanOutCountsCopiesRatherThanEvents() {
        metrics.recordBroadcast(3);
        metrics.recordTyping(2);

        // one message can reach many members, so these count deliveries
        assertThat(count("streamline.broadcasts.sent")).isEqualTo(3);
        assertThat(count("streamline.typing.sent")).isEqualTo(2);
    }

    @Test
    void aFanOutToNobodyCountsNothing() {
        metrics.recordBroadcast(0);

        assertThat(count("streamline.broadcasts.sent")).isZero();
    }
}
