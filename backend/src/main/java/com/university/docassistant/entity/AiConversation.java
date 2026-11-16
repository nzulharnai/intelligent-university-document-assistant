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
 * US-14: a saved conversation with the AI assistant (POST /ai/ask). It belongs to one user and is never shown to
 * anyone else. {@code course} is the scope it was started with: null for "Все мои курсы", otherwise one course; a
 * continued conversation keeps that scope. Deleted with its user and with its course.
 */
@Entity
@Table(name = "ai_conversations", indexes = @Index(name = "idx_ai_conversations_user_updated",
        columnList = "user_id, updated_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConversation {

    public static final int MAX_TITLE_LENGTH = 120;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Course course;

    /** The first question, shortened: the name in "История". */
    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** The time of the last question; the history is sorted by it. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
