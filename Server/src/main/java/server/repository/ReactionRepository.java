package server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import server.model.MessageReaction;

import java.util.List;

@Repository
public interface ReactionRepository extends JpaRepository<MessageReaction, Long> {

    /** Every reaction on one message, oldest first so counts are stable. */
    List<MessageReaction> findByMessageIdOrderByIdAsc(Long messageId);

    /** All reactions across a page of messages, for summarising history. */
    List<MessageReaction> findByMessageIdInOrderByIdAsc(List<Long> messageIds);

    /**
     * Removes one person's reaction, scoped by room.
     *
     * Room-scoped like every other moderation query: an id from another room
     * must not be reachable by guessing.
     *
     * @return 1 when a reaction was removed, 0 when there was none
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MessageReaction r where r.messageId = :messageId "
            + "and r.roomId = :roomId and r.username = :username and r.emoji = :emoji")
    int removeReaction(@Param("messageId") Long messageId,
            @Param("roomId") String roomId,
            @Param("username") String username,
            @Param("emoji") String emoji);

    boolean existsByMessageIdAndUsernameAndEmoji(Long messageId, String username, String emoji);
}
