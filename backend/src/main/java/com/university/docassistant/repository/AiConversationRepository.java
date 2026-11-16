package com.university.docassistant.repository;

import com.university.docassistant.entity.AiConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/** US-14: saved conversations with the AI assistant. Every query is bound to the owner, so nobody reads another's. */
@Repository
public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    /** One row of "История": the conversation, its course and how many questions it has. */
    record ConversationRow(Long id, String title, Long courseId, String courseTitle, String courseCode,
                           LocalDateTime createdAt, LocalDateTime updatedAt, Long turnCount) {
    }

    String ROW_SELECT = """
            SELECT new com.university.docassistant.repository.AiConversationRepository$ConversationRow(
                v.id, v.title, c.id, c.title, c.code, v.createdAt, v.updatedAt,
                (SELECT COUNT(t) FROM AiConversationTurn t WHERE t.conversation.id = v.id))
            FROM AiConversation v LEFT JOIN v.course c
            """;

    @Query(value = ROW_SELECT + "WHERE v.user.id = :userId ORDER BY v.updatedAt DESC, v.id DESC",
            countQuery = "SELECT COUNT(v) FROM AiConversation v WHERE v.user.id = :userId")
    Page<ConversationRow> findRowsByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query(ROW_SELECT + "WHERE v.id = :id AND v.user.id = :userId")
    Optional<ConversationRow> findRowByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT v FROM AiConversation v LEFT JOIN FETCH v.course WHERE v.id = :id AND v.user.id = :userId")
    Optional<AiConversation> findOwned(@Param("id") Long id, @Param("userId") Long userId);

    /** 1 when the user's own conversation was deleted (its turns go with it, ON DELETE CASCADE), 0 otherwise. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM ai_conversations WHERE id = :id AND user_id = :userId", nativeQuery = true)
    int deleteOwned(@Param("id") Long id, @Param("userId") Long userId);
}
