package server.api;

/**
 * A room as seen from the outside: who is connected right now, and how much
 * history it has accumulated.
 *
 * @param roomId       identifier used in the WebSocket path
 * @param members      sessions currently joined
 * @param storedMessages messages persisted for this room
 */
public record RoomSummary(String roomId, int members, long storedMessages) {
}
