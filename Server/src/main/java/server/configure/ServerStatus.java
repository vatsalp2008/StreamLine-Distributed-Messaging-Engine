package server.configure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.util.concurrent.Executor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Read-only operational endpoints for health checks and live traffic inspection.
 */
@RestController
public class ServerStatus {

    private static final Logger log = LoggerFactory.getLogger(ServerStatus.class);

    /** Kept short so a hung database fails the probe rather than stalling it. */
    private static final int READINESS_TIMEOUT_SECONDS = 2;

    private final ChatServerWSHandler handler;
    private final DataSource dataSource;

    private final StreamlineProperties properties;

    /** The bounded pool behind async persistence, used to report saturation. */
    private final Executor persistenceExecutor;

    /** Reloadable room secrets, reported so rotation can be observed. */
    private final RoomTokenStore roomTokens;

    public ServerStatus(ChatServerWSHandler handler, DataSource dataSource,
            StreamlineProperties properties,
            @Qualifier(AsyncConfig.PERSISTENCE_EXECUTOR) Executor persistenceExecutor,
            RoomTokenStore roomTokens) {
        this.handler = handler;
        this.dataSource = dataSource;
        this.properties = properties;
        this.persistenceExecutor = persistenceExecutor;
        this.roomTokens = roomTokens;
    }

    /**
     * @return how full the write queue is, from 0 to 1, or 0 when unknown
     */
    private double writeQueueSaturation() {
        if (!(persistenceExecutor instanceof ThreadPoolTaskExecutor pool)) {
            return 0.0;
        }

        var queue = pool.getThreadPoolExecutor().getQueue();
        int capacity = queue.size() + queue.remainingCapacity();
        return capacity == 0 ? 0.0 : (double) queue.size() / capacity;
    }

    /**
     * Liveness probe. Deliberately does no I/O so it stays cheap under load.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> serverStatus() {
        return ResponseEntity.ok(Map.of("status", "RUNNING"));
    }

    /**
     * Readiness probe: reports whether this instance can actually serve traffic.
     *
     * Unlike /health this touches the database, because an instance whose
     * datasource is unreachable is running but cannot persist a message. Returns
     * 503 when not ready, so a load balancer stops routing to it instead of
     * sending traffic into a broken node.
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> readiness() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(READINESS_TIMEOUT_SECONDS)) {
                return ResponseEntity.ok(Map.of("status", "READY", "database", "UP"));
            }
            return notReady("database connection is not valid");
        } catch (SQLException e) {
            log.warn("Readiness check failed: {}", e.getMessage());
            return notReady(e.getMessage());
        }
    }

    private ResponseEntity<Map<String, String>> notReady(String reason) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "NOT_READY", "database", "DOWN", "reason", reason));
    }

    /**
     * Live snapshot of room membership, useful for watching a load test in flight.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activeRooms", handler.getActiveRoomCount());
        body.put("joinedSessions", handler.getJoinedSessionCount());
        body.put("roomOccupancy", handler.getRoomOccupancy());

        // Usage without the cap does not say whether the server is about to
        // start refusing joins, which is the question this endpoint is for.
        Map<String, Object> capacity = new LinkedHashMap<>();
        capacity.put("maxRooms", properties.getLimits().getMaxRooms());
        capacity.put("maxMembersPerRoom", properties.getLimits().getMaxMembersPerRoom());
        capacity.put("retentionDays", properties.getRetention().getDays());
        body.put("limits", capacity);
        body.put("writeQueueSaturation", writeQueueSaturation());

        // Rotation is a background file read; without this there is no way to
        // tell "the file has no rooms in it" from "the file has not been read".
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("fileConfigured", roomTokens.isFileConfigured());
        tokens.put("roomsFromFile", roomTokens.reloadedCount());
        tokens.put("lastError", roomTokens.lastError());
        body.put("roomTokens", tokens);

        return ResponseEntity.ok(body);
    }
}
