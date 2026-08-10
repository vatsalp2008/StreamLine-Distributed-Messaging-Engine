package server.configure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Gauges for the pool that writes messages to the database.
 *
 * The pool is deliberately bounded with a caller-runs policy, so when writes
 * cannot keep up the WebSocket threads start doing them inline. That is the
 * intended back pressure, but from the outside it looks only like latency
 * getting worse: nothing reported how full the queue was.
 */
@Component
public class PersistenceGauges {

    public PersistenceGauges(MeterRegistry registry,
            @Qualifier(AsyncConfig.PERSISTENCE_EXECUTOR) Executor executor) {

        if (!(executor instanceof ThreadPoolTaskExecutor pool)) {
            // a different executor implementation has nothing to report
            return;
        }

        Gauge.builder("streamline.persistence.queue_depth", pool,
                        p -> p.getThreadPoolExecutor().getQueue().size())
                .description("Writes waiting for a persistence thread")
                .register(registry);

        Gauge.builder("streamline.persistence.queue_capacity", pool,
                        p -> p.getThreadPoolExecutor().getQueue().size()
                                + p.getThreadPoolExecutor().getQueue().remainingCapacity())
                .description("Queue size at which writes start running on the caller")
                .register(registry);

        Gauge.builder("streamline.persistence.active", pool,
                        p -> p.getThreadPoolExecutor().getActiveCount())
                .description("Threads currently writing")
                .register(registry);

        Gauge.builder("streamline.persistence.completed", pool,
                        p -> p.getThreadPoolExecutor().getCompletedTaskCount())
                .description("Writes finished since startup")
                .register(registry);
    }
}
