package server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import server.configure.StreamlineProperties;
import server.repository.MessageRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Removes messages older than the configured retention window.
 *
 * Nothing previously deleted anything, so the table grew for the life of the
 * deployment and the only bound on disk use was how long the server had been
 * running. Retention is opt-in: a zero or negative window keeps everything, so
 * an existing deployment does not silently start discarding history.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final MessageRepository messageRepository;
    private final StreamlineProperties.Retention settings;
    private final Clock clock;

    /**
     * Rows removed since startup. A scheduled job that only logs gives no way to
     * tell "nothing was old enough" from "the sweep stopped running".
     */
    private final Counter pruned;

    @org.springframework.beans.factory.annotation.Autowired
    public RetentionService(MessageRepository messageRepository, StreamlineProperties properties,
            MeterRegistry registry) {
        this(messageRepository, properties, Clock.systemUTC(), registry);
    }

    /**
     * @param clock source of "now"; tests supply their own rather than waiting
     */
    RetentionService(MessageRepository messageRepository, StreamlineProperties properties,
            Clock clock) {
        this(messageRepository, properties, clock,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    RetentionService(MessageRepository messageRepository, StreamlineProperties properties,
            Clock clock, MeterRegistry registry) {
        this.messageRepository = messageRepository;
        this.settings = properties.getRetention();
        this.clock = clock;
        this.pruned = Counter.builder("streamline.retention.pruned")
                .description("Messages removed by the retention sweep")
                .register(registry);
    }

    /**
     * @return the number of messages removed, for tests and logging
     */
    @Scheduled(fixedDelayString = "${streamline.retention.sweep-interval-ms:3600000}")
    @Transactional
    public int prune() {
        Duration window = Duration.ofDays(settings.getDays());
        if (window.isZero() || window.isNegative()) {
            return 0;
        }

        Instant cutoff = clock.instant().minus(window);
        int removed = messageRepository.deleteOlderThan(cutoff);

        if (removed > 0) {
            pruned.increment(removed);
            log.info("Retention removed {} messages older than {}", removed, cutoff);
        }
        return removed;
    }
}
