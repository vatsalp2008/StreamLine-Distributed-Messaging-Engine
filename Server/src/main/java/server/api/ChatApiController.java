package server.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import server.configure.ChatServerWSHandler;
import server.model.ChatMessage;
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

    /** Matches the 50 messages replayed over WebSocket on join. */
    static final int DEFAULT_PAGE_SIZE = 50;

    /** Upper bound on a single response, so one request cannot drain the table. */
    static final int MAX_PAGE_SIZE = 200;

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

    /**
     * Reads a page of a room's history, newest first.
     *
     * Page size is clamped rather than rejected, so a caller asking for 10,000
     * messages gets the maximum instead of an error, and cannot use the
     * parameter to pull the whole table in one request.
     *
     * @param roomId room to read
     * @param page   zero-based page number
     * @param size   messages per page, clamped to 1..MAX_PAGE_SIZE
     */
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<MessagePage> messages(
            @PathVariable String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative, got " + page);
        }

        int effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<ChatMessage> result = chatService.getMessagePage(roomId, PageRequest.of(page, effectiveSize));

        return ResponseEntity.ok(MessagePage.from(roomId, result));
    }
}
