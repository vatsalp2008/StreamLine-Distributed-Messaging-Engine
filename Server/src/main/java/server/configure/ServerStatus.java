package server.configure;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import java.util.*;

/**
 * Read-only operational endpoints for health checks and live traffic inspection.
 */
@RestController
public class ServerStatus {

    private final ChatServerWSHandler handler;

    public ServerStatus(ChatServerWSHandler handler) {
        this.handler = handler;
    }

    /**
     * Liveness probe. Deliberately does no I/O so it stays cheap under load.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> serverStatus() {
        return ResponseEntity.ok(Map.of("status", "RUNNING"));
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
        return ResponseEntity.ok(body);
    }
}
