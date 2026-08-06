package server.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import server.configure.ChatServerWSHandler;
import server.service.ChatService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only HTTP view of chat state.
 *
 * The WebSocket endpoint is the only way to take part in a conversation; this
 * API exists so tools and dashboards can inspect rooms without opening a socket.
 */
@RestController
@RequestMapping("/api")
public class ChatApiController {

    private final ChatServerWSHandler handler;
    private final ChatService chatService;

    public ChatApiController(ChatServerWSHandler handler, ChatService chatService) {
        this.handler = handler;
        this.chatService = chatService;
    }

    /**
     * Lists rooms that currently hold at least one joined session.
     *
     * A room with no members is not listed even if it has stored history, since
     * the live room map is what makes a room "active".
     */
    @GetMapping("/rooms")
    public ResponseEntity<List<RoomSummary>> rooms() {
        Map<String, Integer> occupancy = handler.getRoomOccupancy();

        List<RoomSummary> summaries = new ArrayList<>(occupancy.size());
        occupancy.forEach((roomId, members) ->
                summaries.add(new RoomSummary(roomId, members, chatService.countMessages(roomId))));

        return ResponseEntity.ok(summaries);
    }
}
