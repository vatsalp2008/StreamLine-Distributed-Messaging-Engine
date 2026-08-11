package server.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import server.model.ChatMessage;

import java.time.Instant;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * Most recent messages in a room, newest first.
     *
     * Ties are broken by id. Clients routinely send several messages inside the
     * same millisecond, and without a second sort key their relative order is
     * whatever the database happens to return, so replayed history could differ
     * between two joins of the same room.
     */
    List<ChatMessage> findTop50ByRoomIdOrderByTimestampDescIdDesc(String roomId);

    /**
     * Page through a room's history, newest first.
     */
    Page<ChatMessage> findByRoomIdOrderByTimestampDescIdDesc(String roomId, Pageable pageable);

    /**
     * @return how many messages the room has stored
     */
    long countByRoomId(String roomId);

    /**
     * Deletes messages older than the cutoff.
     *
     * @param cutoff the oldest timestamp to keep
     * @return how many rows were removed
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "delete from ChatMessage m where m.timestamp < :cutoff")
    int deleteOlderThan(@org.springframework.data.repository.query.Param("cutoff") Instant cutoff);

    /**
     * Removes one message, but only if it belongs to the given room.
     *
     * Scoped by room so a caller holding one room's token cannot delete a
     * message from another simply by guessing its id.
     *
     * @return the number of rows removed: 1 on success, 0 if it did not exist
     *         in that room
     */
    @org.springframework.data.jpa.repository.Modifying
    long deleteByIdAndRoomId(Long id, String roomId);

    /**
     * Case-insensitive substring search within a room, newest first.
     *
     * Backed by a LIKE scan rather than a full-text index, which is adequate for
     * the volumes this server holds and avoids a second storage engine.
     */
    Page<ChatMessage> findByRoomIdAndMessageContainingIgnoreCaseOrderByTimestampDescIdDesc(
            String roomId, String text, Pageable pageable);

    /**
     * The same search restricted to one author.
     */
    Page<ChatMessage> findByRoomIdAndUsernameIgnoreCaseAndMessageContainingIgnoreCaseOrderByTimestampDescIdDesc(
            String roomId, String username, String text, Pageable pageable);
}
