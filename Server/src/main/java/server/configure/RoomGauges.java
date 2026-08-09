package server.configure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Live gauges for room occupancy and how close it is to the configured caps.
 *
 * Counters say how much traffic has been handled; these say how much room is
 * left. Without them a server can sit at its room limit, refusing joins, while
 * every counter still looks healthy.
 */
@Component
public class RoomGauges {

    public RoomGauges(MeterRegistry registry, ChatServerWSHandler handler,
            StreamlineProperties properties) {

        Gauge.builder("streamline.rooms.active", handler,
                        ChatServerWSHandler::getActiveRoomCount)
                .description("Rooms currently holding at least one joined session")
                .register(registry);

        Gauge.builder("streamline.rooms.sessions", handler,
                        ChatServerWSHandler::getJoinedSessionCount)
                .description("Sessions that have joined a room")
                .register(registry);

        // Reported so an alert can fire on the ratio rather than on a bare count,
        // which means nothing without knowing the cap.
        Gauge.builder("streamline.rooms.limit", properties,
                        p -> p.getLimits().getMaxRooms())
                .description("Configured maximum number of rooms, 0 when unlimited")
                .register(registry);

        Gauge.builder("streamline.rooms.members_limit", properties,
                        p -> p.getLimits().getMaxMembersPerRoom())
                .description("Configured maximum members per room, 0 when unlimited")
                .register(registry);
    }
}
