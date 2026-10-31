package com.university.docassistant.dto;

import com.university.docassistant.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTOs for tasks.
 */
public final class TaskDtos {

    private TaskDtos() {
    }

    public record UpdateStatusRequest(
            @NotNull TaskStatus status
    ) {
    }

    /** "Мои задачи" filter by state; ALL by default. Overdue and "к сдаче" are decided by the shared clock. */
    public enum TaskStateFilter {
        ALL,
        NOT_STARTED,
        IN_PROGRESS,
        /** Handed in, waiting for a grade (on time or late). */
        SUBMITTED,
        /** The current hand-in came after the deadline, graded or not. */
        LATE,
        GRADED,
        /** Nothing handed in and the deadline has passed. */
        OVERDUE,
        /** Nothing handed in yet, the deadline not passed and the assignment still open: "К сдаче". */
        TO_DO
    }

    /** "К сдаче: N · Просрочено: M · Оценено: K". */
    public record TaskSummary(long toDo, long overdue, long graded) {
    }
}
