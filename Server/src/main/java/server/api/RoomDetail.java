package server.api;

import java.util.List;

/**
 * Everything known about a single room.
 *
 * Separate from {@link RoomSummary}: the room list stays cheap because it does
 * not carry a member list per room, while this view names who is present.
 *
 * @param roomId         identifier used in the WebSocket path
 * @param members        usernames currently joined, sorted and de-duplicated
 * @param sessions       open sessions, which exceeds members when a user connects twice
 * @param storedMessages messages persisted for this room
 */
public record RoomDetail(String roomId, List<String> members, int sessions, long storedMessages) {
}
