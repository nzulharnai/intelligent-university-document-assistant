package com.university.docassistant.dto;

import com.university.docassistant.entity.AssignmentStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Request/response DTOs for course assignments.
 */
public final class AssignmentDtos {

    private AssignmentDtos() {
    }

    /** Built directly by the JPQL constructor expressions in AssignmentRepository; see {@link #SELECT}. */
    public record AssignmentResponse(
            Long id,
            String title,
            String description,
            LocalDateTime deadline,
            AssignmentStatus status,
            Long courseId,
            String courseTitle,
            String courseCode,
            Long sourceDocumentId,
            String sourceDocumentName,
            LocalDateTime createdAt,
            /** For an AI draft: the existing assignment of the course it looks like; null when none. */
            Long duplicateOfId,
            String duplicateOfTitle,
            LocalDateTime duplicateOfDeadline,
            /** The source document's last AI analysis covered only part of its text: drafts may be missing. */
            boolean sourceAnalysisPartial,
            /** Submissions after the deadline are accepted (marked late). */
            boolean allowLate,
            /** The highest grade, 1..1000; null when not graded with points. */
            Integer maxScore
    ) {
        /** The same assignment showing {@code name} as its source document's name (US-09: masked for students). */
        public AssignmentResponse withSourceDocumentName(String name) {
            return new AssignmentResponse(id, title, description, deadline, status, courseId, courseTitle, courseCode,
                    sourceDocumentId, name, createdAt, duplicateOfId, duplicateOfTitle, duplicateOfDeadline,
                    sourceAnalysisPartial, allowLate, maxScore);
        }

        /** Select list matching the canonical constructor; the queries alias Assignment as a, course as c. */
        public static final String SELECT = """
                SELECT new com.university.docassistant.dto.AssignmentDtos$AssignmentResponse(
                    a.id, a.title, a.requirements, a.deadline, a.status, c.id, c.title, c.code,
                    d.id, d.originalFileName, a.createdAt, dup.id, dup.title, dup.deadline,
                    COALESCE(d.aiExtractionPartial, false), a.allowLate, a.maxScore)
                FROM Assignment a JOIN a.course c LEFT JOIN a.sourceDocument d LEFT JOIN a.duplicateOf dup
                """;
    }

    /** Drafts of one course to publish together; all or none. */
    public record PublishRequest(
            @NotEmpty @Size(max = 200) List<@NotNull Long> ids
    ) {
    }

    /** How many drafts became ACTIVE and how many student tasks that created. */
    public record PublishResponse(int published, int tasksCreated) {
    }

    /**
     * PATCH /assignments/{id}: the editable fields, all of them (null description, deadline or maxScore clears it;
     * null allowLate keeps it). The requirements travel as "description", like in {@link CreateAssignmentRequest}
     * and {@link AssignmentResponse}.
     */
    public record UpdateAssignmentRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 2_000) String description,
            LocalDateTime deadline,
            Boolean allowLate,
            @Min(1) @Max(1_000) Integer maxScore
    ) {
        /** Title, description and deadline only: late submissions stay as they are, no points. */
        public UpdateAssignmentRequest(String title, String description, LocalDateTime deadline) {
            this(title, description, deadline, null, null);
        }
    }

    public record CreateAssignmentRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 2_000) String description,
            LocalDateTime deadline
    ) {
    }
}
