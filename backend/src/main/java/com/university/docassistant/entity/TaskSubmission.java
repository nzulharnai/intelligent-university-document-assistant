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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * A file a student handed in for one of their {@link Task}s (Moodle-style submission). This is the only way a
 * STUDENT uploads anything: course documents are added by the course's teacher or ADMIN. Submissions are not course
 * documents, so they are never indexed for search or Q&A, and only the student and the course's teacher (or ADMIN)
 * can read them. Rows go away with their task (ON DELETE CASCADE), however the task is removed.
 * One hand-in (one request, all its files) is one {@code version}: the task's highest version is its current
 * submission, earlier ones stay as history and are never removed by a resubmission.
 */
@Entity
@Table(name = "task_submissions", indexes = {
        @Index(name = "idx_task_submissions_task_id", columnList = "task_id"),
        @Index(name = "idx_task_submissions_student_id", columnList = "student_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private User student;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    /** Random name on disk, so user-supplied names never reach the file system. */
    @Column(name = "stored_file_name", nullable = false, unique = true, length = 64)
    private String storedFileName;

    @Column(name = "content_type", nullable = false, length = 150)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private LocalDateTime submittedAt;

    /**
     * 1 for the first hand-in of the task, then one more per resubmission. Rows from before versions existed are all
     * version 1 (the column default): files used to pile up into one submission.
     */
    @Column(nullable = false)
    @ColumnDefault("1")
    @Builder.Default
    private int version = 1;

    @PrePersist
    protected void onCreate() {
        if (submittedAt == null) {
            submittedAt = LocalDateTime.now();
        }
    }
}
