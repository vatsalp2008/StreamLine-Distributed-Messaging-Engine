package server.api;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * One page of room history.
 *
 * Spring's Page serialises with an unstable internal shape, so the fields the
 * API promises are spelled out here instead.
 *
 * @param roomId        the room being read
 * @param messages      the page contents, newest first
 * @param page          zero-based page number
 * @param size          requested page size
 * @param totalMessages messages stored for the room
 * @param totalPages    pages available at this size
 * @param hasMore       whether another page follows
 */
public record MessagePage(
        String roomId,
        List<MessageView> messages,
        int page,
        int size,
        long totalMessages,
        int totalPages,
        boolean hasMore) {

    public static MessagePage from(String roomId, Page<server.model.ChatMessage> source) {
        return new MessagePage(
                roomId,
                source.getContent().stream().map(MessageView::from).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.hasNext());
    }
}
