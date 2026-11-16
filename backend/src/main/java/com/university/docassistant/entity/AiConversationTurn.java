package com.university.docassistant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * US-14: one question of a saved conversation and the answer it got. {@code answerJson} is the whole answer as the
 * user saw it (DocumentAnswerResponse: text, sources, assignments, tasks), so the history shows the same sources
 * again. Only answers are stored; a question the AI could not answer (503) leaves no turn.
 */
@Entity
@Table(name = "ai_conversation_turns", indexes = @Index(name = "idx_ai_turns_conversation",
        columnList = "conversation_id, id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConversationTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private AiConversation conversation;

    /** As the user typed it (only they ever see it); the AI got the masked copy. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "answer_json", nullable = false, columnDefinition = "TEXT")
    private String answerJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
