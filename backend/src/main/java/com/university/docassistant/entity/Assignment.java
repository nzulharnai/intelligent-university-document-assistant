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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Course-level work with a deadline: found by the AI in a document ({@code sourceDocument} set) or created by the
 * course's teacher. Every enrolled student gets one {@link Task} per assignment (see TaskRepository.fanOut).
 */
@Entity
@Table(name = "assignments", indexes = {
        @Index(name = "idx_assignments_course_id", columnList = "course_id"),
        @Index(name = "idx_assignments_source_document_id", columnList = "source_document_id"),
        @Index(name = "idx_assignments_deadline", columnList = "deadline")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    /** What the work requires; kept in the existing "description" column, which the API still calls description. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String requirements;

    private LocalDateTime deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AssignmentStatus status = AssignmentStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    /** The document the AI found it in; null for assignments created by hand. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_document_id")
    private Document sourceDocument;

    /**
     * Set once the course teacher takes an AI assignment over (edits, closes or reopens it): the title+deadline key it
     * had before, so a later re-analysis still recognises it but never changes or removes it. Null otherwise.
     */
    @Column(name = "ai_match_key", length = 300)
    private String aiMatchKey;

    /**
     * An AI draft that looks like this existing assignment of the course (AssignmentTitles.likelySame): shown to the
     * teacher as "Похоже на", never merged automatically. Cleared by the teacher ("Это другое задание") or when that
     * assignment is deleted (ON DELETE SET NULL).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "duplicate_of_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Assignment duplicateOf;

    /** Submissions after the deadline are accepted (and marked late); false refuses them. */
    @Column(name = "allow_late", nullable = false)
    @ColumnDefault("true")
    @Builder.Default
    private boolean allowLate = true;

    /** The highest grade, 1..1000; null when the assignment is not graded with points. */
    @Column(name = "max_score")
    private Integer maxScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
