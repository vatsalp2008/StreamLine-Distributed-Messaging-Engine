package server.configure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gauges describing the bounded pool that writes messages.
 *
 * The pool falls back to running writes on the caller when it fills, which is
 * the intended back pressure but is invisible from outside: it shows up only as
 * the WebSocket threads getting slower. These report how close it is to that.
 */
class PersistenceGaugesTest {

    private MeterRegistry registry;
    private ThreadPoolTaskExecutor pool;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(8);
        pool.initialize();

        new PersistenceGauges(registry, pool);
    }

    @AfterEach
    void shutdown() {
        pool.shutdown();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    void anIdlePoolReportsAnEmptyQueue() {
        assertThat(gauge("streamline.persistence.queue_depth")).isZero();
        assertThat(gauge("streamline.persistence.active")).isZero();
    }

    @Test
    void theCapacityIsTheConfiguredQueueSize() {
        // a depth means nothing without the size it is approaching
        assertThat(gauge("streamline.persistence.queue_capacity")).isEqualTo(8);
    }

    @Test
    void queuedWorkIsVisible() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);

        // occupy the single thread, so everything after this queues
        pool.execute(() -> {
            running.countDown();
            try {
                block.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

        pool.execute(() -> { });
        pool.execute(() -> { });

        assertThat(gauge("streamline.persistence.queue_depth")).isEqualTo(2);
        assertThat(gauge("streamline.persistence.active")).isEqualTo(1);

        block.countDown();
    }

    @Test
    void completedWorkIsCounted() throws Exception {
        CountDownLatch done = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            pool.execute(done::countDown);
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        // the executor updates its count just after the task returns
        for (int i = 0; i < 50 && gauge("streamline.persistence.completed") < 3; i++) {
            Thread.sleep(20);
        }
        assertThat(gauge("streamline.persistence.completed")).isEqualTo(3);
    }

    @Test
    void theQueueDrainsBackToEmpty() throws Exception {
        CountDownLatch done = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            pool.execute(done::countDown);
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < 50 && gauge("streamline.persistence.queue_depth") > 0; i++) {
            Thread.sleep(20);
        }
        assertThat(gauge("streamline.persistence.queue_depth")).isZero();
    }

    @Test
    void anUnknownExecutorTypeRegistersNothingRatherThanFailing() {
        MeterRegistry other = new SimpleMeterRegistry();
        Executor plain = Runnable::run;

        new PersistenceGauges(other, plain);

        // a different executor has no queue to describe; it must not throw
        assertThat(other.find("streamline.persistence.queue_depth").gauge()).isNull();
    }

    @Test
    void everyGaugeCarriesADescription() {
        for (String name : new String[] {"streamline.persistence.queue_depth",
                "streamline.persistence.queue_capacity", "streamline.persistence.active",
                "streamline.persistence.completed"}) {
            assertThat(registry.get(name).gauge().getId().getDescription())
                    .as("%s should be self-describing in a metrics scrape", name)
                    .isNotBlank();
        }
    }
}
