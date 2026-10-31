package com.university.docassistant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A student's personal copy of an {@link Assignment} ("My tasks"): its own status, with title, deadline, document
 * and course copied from the assignment. Rows without an assignment predate assignments.
 */
@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_tasks_user_id", columnList = "user_id"),
        @Index(name = "idx_tasks_deadline", columnList = "deadline"),
        @Index(name = "idx_tasks_document_id", columnList = "document_id"),
        @Index(name = "idx_tasks_course_id", columnList = "course_id"),
        @Index(name = "idx_tasks_assignment_id", columnList = "assignment_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private LocalDateTime deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id")
    private Assignment assignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Time of the latest submission (app time zone); null until the student hands something in. */
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /** The latest submission came after the deadline; recomputed when the deadline changes. */
    @Column(nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean late = false;

    /** US-11: the teacher's grade, at most the assignment's max_score. */
    @Column(precision = 6, scale = 2)
    private BigDecimal score;

    /** US-11: the teacher's comment, up to 2000 characters. */
    @Column(columnDefinition = "TEXT")
    private String feedback;

    @Column(name = "graded_at")
    private LocalDateTime gradedAt;

    /**
     * US-13: when the "deadline in 24 hours" reminder was sent; claimed atomically, so it goes out once. Cleared when
     * the deadline moves later, so the new deadline gets its own reminder.
     */
    @Column(name = "reminder_sent_at")
    private LocalDateTime reminderSentAt;

    /** US-11: who graded it; kept as null when that user is deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "graded_by_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User gradedBy;
}
