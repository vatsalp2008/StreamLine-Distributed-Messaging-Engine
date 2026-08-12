package server.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import server.configure.ChatMetrics;
import server.configure.ChatServerWSHandler;
import server.configure.TokenAuthenticator;
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
@Tag(name = "Chat", description = "Rooms, their history, and moderation")
@ApiResponse(responseCode = "429", description = "Rate limited; retry after slowing down",
        content = @io.swagger.v3.oas.annotations.media.Content)
@ApiResponse(responseCode = "401", description = "Missing or wrong token, when auth is enabled",
        content = @io.swagger.v3.oas.annotations.media.Content)
public class ChatApiController {

    /** Matches the 50 messages replayed over WebSocket on join. */
    static final int DEFAULT_PAGE_SIZE = 50;

    /** Upper bound on a single response, so one request cannot drain the table. */
    static final int MAX_PAGE_SIZE = 200;

    private final ChatServerWSHandler handler;
    private final ChatService chatService;
    private final TokenAuthenticator authenticator;
    private final ChatMetrics metrics;

    public ChatApiController(ChatServerWSHandler handler, ChatService chatService,
            TokenAuthenticator authenticator, ChatMetrics metrics) {
        this.handler = handler;
        this.chatService = chatService;
        this.authenticator = authenticator;
        this.metrics = metrics;
    }

    /**
     * Lists rooms that currently hold at least one joined session.
     *
     * A room with no members is not listed even if it has stored history, since
     * the live room map is what makes a room "active".
     */
    @Operation(summary = "List active rooms",
            description = "Rooms holding at least one joined session. A room with stored "
                    + "history but no members is not listed, since live membership is what "
                    + "makes a room active.")
    @ApiResponse(responseCode = "200", description = "Active rooms, ordered by room id")
    @GetMapping("/rooms")
    public ResponseEntity<List<RoomSummary>> rooms(HttpServletRequest request) {
        String presented = presentedToken(request);
        Map<String, Integer> occupancy = handler.getRoomOccupancy();

        List<RoomSummary> summaries = new ArrayList<>(occupancy.size());
        occupancy.forEach((roomId, members) -> {
            // A room with its own secret is not named to holders of the shared
            // token: listing it would disclose that it exists, and to whom, to
            // exactly the people its own token is meant to exclude.
            if (authenticator.hasRoomToken(roomId)
                    && !authenticator.isAuthorisedForRoom(roomId, presented)) {
                return;
            }
            summaries.add(new RoomSummary(roomId, members, chatService.countMessages(roomId)));
        });

        return ResponseEntity.ok(summaries);
    }

    /**
     * @return the token this caller presented, by header or query parameter
     */
    private String presentedToken(HttpServletRequest request) {
        String fromHeader = request.getHeader(authenticator.headerName());
        return fromHeader != null ? fromHeader
                : request.getParameter(authenticator.queryParamName());
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
    @Operation(summary = "Search a room's history",
            description = "Case-insensitive substring match on message text, newest first, "
                    + "optionally restricted to one author. Returns the same page shape as "
                    + "the history endpoint.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching messages"),
            @ApiResponse(responseCode = "400", description = "Missing or invalid query")
    })
    @GetMapping("/rooms/{roomId}/search")
    public ResponseEntity<MessagePage> search(
            @Parameter(description = "Room identifier used in the WebSocket path")
            @PathVariable String roomId,
            @Parameter(description = "Text to look for, case-insensitive")
            @RequestParam String q,
            @Parameter(description = "Restrict results to this author")
            @RequestParam(required = false) String username,
            @Parameter(description = "Zero-based page number")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Results per page; clamped to 1..200")
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative, got " + page);
        }
        if (q == null || q.isBlank()) {
            throw new IllegalArgumentException("q must not be empty");
        }

        int effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<ChatMessage> result = chatService.searchMessages(
                roomId, q.trim(), username, PageRequest.of(page, effectiveSize));

        return ResponseEntity.ok(MessagePage.from(roomId, result));
    }

    @Operation(summary = "Edit a message",
            description = "Replaces the text of one message. The id is the one reported by a "
                    + "DELIVERED receipt. The original timestamp is kept, so an edit does not "
                    + "reorder the room's history.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated message"),
            @ApiResponse(responseCode = "400", description = "Missing or overlong text"),
            @ApiResponse(responseCode = "404", description = "No such message in that room")
    })
    @PatchMapping("/rooms/{roomId}/messages/{messageId}")
    public ResponseEntity<MessageView> editMessage(
            @Parameter(description = "Room identifier used in the WebSocket path")
            @PathVariable String roomId,
            @Parameter(description = "Stored message id, as carried by a DELIVERED receipt")
            @PathVariable Long messageId,
            @org.springframework.web.bind.annotation.RequestBody
            @jakarta.validation.Valid EditRequest request) {

        return chatService.editMessage(roomId, messageId, request.message())
                .map(updated -> {
                    metrics.recordEdited();
                    handler.announceEdit(roomId, messageId, updated.getMessage());
                    return ResponseEntity.ok(MessageView.from(updated));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete a message",
            description = "Removes one message from a room. The id is the one reported by a "
                    + "DELIVERED receipt. Scoped to the room, so a token for one room cannot "
                    + "delete another room's messages by guessing ids.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The message was removed"),
            @ApiResponse(responseCode = "404", description = "No such message in that room")
    })
    @DeleteMapping("/rooms/{roomId}/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(
            @Parameter(description = "Room identifier used in the WebSocket path")
            @PathVariable String roomId,
            @Parameter(description = "Stored message id, as carried by a DELIVERED receipt")
            @PathVariable Long messageId) {

        if (!chatService.deleteMessage(roomId, messageId)) {
            // 404 rather than 204: a caller deleting the wrong id should hear
            // about it instead of believing the message is gone
            return ResponseEntity.notFound().build();
        }

        metrics.recordDeleted();
        handler.announceRedaction(roomId, messageId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Describe one room",
            description = "Members present, open session count and stored message total. "
                    + "A room nobody has joined reports zero members rather than 404, so a "
                    + "client can poll a room before anyone arrives.")
    @ApiResponse(responseCode = "200", description = "The room's current state")
    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<RoomDetail> room(
            @Parameter(description = "Room identifier used in the WebSocket path")
            @PathVariable String roomId) {

        List<String> members = handler.getRoomMembers(roomId);
        int sessions = handler.getRoomOccupancy().getOrDefault(roomId, 0);

        return ResponseEntity.ok(new RoomDetail(
                roomId, members, sessions, chatService.countMessages(roomId)));
    }

    @Operation(summary = "Read room history",
            description = "Messages for a room, newest first. An unknown room returns an "
                    + "empty page rather than a 404, so polling a room that has not been "
                    + "used yet is not an error.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of history"),
            @ApiResponse(responseCode = "400", description = "Negative or non-numeric page")
    })
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<MessagePage> messages(
            @Parameter(description = "Room identifier used in the WebSocket path")
            @PathVariable String roomId,
            @Parameter(description = "Zero-based page number")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Messages per page; clamped to 1..200")
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative, got " + page);
        }

        int effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<ChatMessage> result = chatService.getMessagePage(roomId, PageRequest.of(page, effectiveSize));

        return ResponseEntity.ok(MessagePage.from(roomId, result));
    }
}
