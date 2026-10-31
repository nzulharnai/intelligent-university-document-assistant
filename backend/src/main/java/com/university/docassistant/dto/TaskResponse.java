package com.university.docassistant.dto;

import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.TaskStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A task with the names of its document and course. Built directly by the JPQL constructor expressions in
 * TaskRepository, so listing tasks never loads a document's extracted text. Score and feedback are the task's
 * student's own; only the student's task endpoints (/tasks/my, /tasks/upcoming, PUT /tasks/{id}/status) return it.
 */
public record TaskResponse(
        Long id,
        String title,
        String description,
        LocalDateTime deadline,
        TaskStatus status,
        Long documentId,
        String documentName,
        Long courseId,
        String courseTitle,
        String courseCode,
        Long assignmentId,
        /** CLOSED: the assignment takes no more submissions; null for tasks without an assignment. */
        AssignmentStatus assignmentStatus,
        /** Files the student has handed in for this task. */
        Long submissionCount,
        /** Time of the latest submission; null before the first one. */
        LocalDateTime submittedAt,
        /** The latest submission came after the deadline. */
        boolean late,
        BigDecimal score,
        /** The assignment's highest grade; null when not graded with points or without an assignment. */
        Integer maxScore,
        String feedback,
        LocalDateTime gradedAt,
        /** Whether the assignment accepts submissions after the deadline; null for tasks without an assignment. */
        Boolean allowLate
) {
    /** The same task showing {@code name} as its document's name (US-09: masked for students). */
    public TaskResponse withDocumentName(String name) {
        return new TaskResponse(id, title, description, deadline, status, documentId, name, courseId, courseTitle,
                courseCode, assignmentId, assignmentStatus, submissionCount, submittedAt, late, score, maxScore,
                feedback, gradedAt, allowLate);
    }

    /** Select list matching the canonical constructor; the queries alias Task as t, document as d, course as c. */
    public static final String SELECT = """
            SELECT new com.university.docassistant.dto.TaskResponse(
                t.id, t.title, t.description, t.deadline, t.status,
                d.id, d.originalFileName, c.id, c.title, c.code, a.id, a.status,
                (SELECT COUNT(s) FROM TaskSubmission s WHERE s.task.id = t.id),
                t.submittedAt, t.late, t.score, a.maxScore, t.feedback, t.gradedAt, a.allowLate)
            FROM Task t LEFT JOIN t.document d LEFT JOIN t.course c LEFT JOIN t.assignment a
            """;
}
