package server.configure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Thread pool backing @Async message persistence.
 *
 * The Spring Boot default executor queues work without bound, so a burst of
 * traffic that outruns the database turns into unbounded heap growth rather
 * than visible pressure. This pool is explicitly bounded and falls back to
 * CallerRunsPolicy, which pushes back on the WebSocket thread instead of
 * silently dropping messages or exhausting memory.
 */
@Configuration
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    public static final String PERSISTENCE_EXECUTOR = "messagePersistenceExecutor";

    @Bean(name = PERSISTENCE_EXECUTOR)
    public Executor messagePersistenceExecutor(StreamlineProperties properties) {
        int corePoolSize = properties.getPersistence().getCorePoolSize();
        int maxPoolSize = properties.getPersistence().getMaxPoolSize();
        int queueCapacity = properties.getPersistence().getQueueCapacity();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("msg-persist-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // let in-flight writes finish so a restart does not lose queued messages
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();

        log.info("Message persistence pool ready (core={}, max={}, queue={})",
                corePoolSize, maxPoolSize, queueCapacity);

        return executor;
    }
}
