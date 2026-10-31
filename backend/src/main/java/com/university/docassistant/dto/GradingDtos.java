package com.university.docassistant.dto;

import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.SubmissionDtos.SubmissionResponse;
import com.university.docassistant.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Grading of students' work (US-11): the teacher's grade request, and the assignment's grading page.
 */
public final class GradingDtos {

    private GradingDtos() {
    }

    /** PUT /tasks/{id}/grade. Score 0..max_score (0..1000 without one), at most one decimal; feedback optional. */
    public record GradeRequest(
            @NotNull BigDecimal score,
            @Size(max = 2_000) String feedback
    ) {
    }

    /** Which students the grading page lists; ALL by default. */
    public enum GradingFilter {
        ALL,
        /** Nothing handed in and not graded. */
        NOT_SUBMITTED,
        /** Handed in, waiting for a grade (on time or late). */
        SUBMITTED,
        /** The current hand-in came after the deadline, graded or not. */
        LATE,
        GRADED
    }

    /** One enrolled student's task of the assignment, with the files of their current hand-in. */
    public record GradingStudent(
            Long taskId,
            Long studentId,
            String studentFullName,
            String studentEmail,
            TaskStatus status,
            LocalDateTime submittedAt,
            boolean late,
            BigDecimal score,
            String feedback,
            LocalDateTime gradedAt,
            List<SubmissionResponse> files
    ) {
    }

    /** The progress line: "Сдали X из Y · с опозданием Z · оценено W". */
    public record GradingSummary(long students, long submitted, long late, long graded) {
    }

    public record GradingResponse(
            AssignmentResponse assignment,
            GradingSummary summary,
            PageResponse<GradingStudent> students
    ) {
    }
}
