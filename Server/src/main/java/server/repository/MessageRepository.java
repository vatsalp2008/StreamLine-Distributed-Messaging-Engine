package server.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import server.model.ChatMessage;
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
}
