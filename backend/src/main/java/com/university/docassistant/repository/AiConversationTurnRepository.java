package com.university.docassistant.repository;

import com.university.docassistant.entity.AiConversationTurn;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** US-14: the questions and answers of saved conversations. */
@Repository
public interface AiConversationTurnRepository extends JpaRepository<AiConversationTurn, Long> {

    /** The latest turns first; the caller reverses them into reading order. */
    @Query("SELECT t FROM AiConversationTurn t WHERE t.conversation.id = :conversationId ORDER BY t.id DESC")
    List<AiConversationTurn> findLatest(@Param("conversationId") Long conversationId, Pageable pageable);

    @Query("SELECT COUNT(t) FROM AiConversationTurn t WHERE t.conversation.id = :conversationId")
    long countByConversationId(@Param("conversationId") Long conversationId);
}
