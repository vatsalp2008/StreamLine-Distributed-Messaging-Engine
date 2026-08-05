package server.configure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes room state as Micrometer gauges so live occupancy shows up under
 * /actuator/metrics alongside the JVM and HTTP metrics, rather than only through
 * the hand rolled /stats endpoint.
 */
@Configuration
public class MetricsConfig {

    public MetricsConfig(MeterRegistry registry, ChatServerWSHandler handler) {
        Gauge.builder("streamline.rooms.active", handler, ChatServerWSHandler::getActiveRoomCount)
                .description("Rooms holding at least one joined session")
                .register(registry);

        Gauge.builder("streamline.sessions.joined", handler, ChatServerWSHandler::getJoinedSessionCount)
                .description("Sessions that have joined a room and not yet left")
                .register(registry);
    }
}
