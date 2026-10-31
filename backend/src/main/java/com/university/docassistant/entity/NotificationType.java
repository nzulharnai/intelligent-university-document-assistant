package com.university.docassistant.entity;

/** What a {@link Notification} is about (US-13). */
public enum NotificationType {
    /** The course's teacher published one or more assignments (the titles are in the body). */
    ASSIGNMENT_PUBLISHED,
    /** An active assignment's deadline moved; the body has the old and the new date. */
    DEADLINE_CHANGED,
    /** The deadline is less than a day away and nothing has been handed in (US-13 step 2). */
    DEADLINE_REMINDER_24H,
    /** The teacher graded the work; the body has the score. */
    TASK_GRADED,
    TEACHER_REQUEST_APPROVED,
    TEACHER_REQUEST_REJECTED
}
