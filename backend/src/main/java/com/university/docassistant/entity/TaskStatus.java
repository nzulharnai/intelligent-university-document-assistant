package com.university.docassistant.entity;

/**
 * A task of a course assignment goes PENDING -> IN_PROGRESS (the student, both ways) -> SUBMITTED (the system, on a
 * submission) -> GRADED (the system, when the teacher grades it). COMPLETED belongs to legacy tasks without an
 * assignment only, whose student still moves them freely between PENDING, IN_PROGRESS and COMPLETED.
 */
public enum TaskStatus {
    PENDING,
    IN_PROGRESS,
    SUBMITTED,
    GRADED,
    COMPLETED;

    /** Set only by the system on assignment tasks, never by the student (see TaskService.updateStatus). */
    public boolean isSystemOnly() {
        return this == SUBMITTED || this == GRADED;
    }
}
