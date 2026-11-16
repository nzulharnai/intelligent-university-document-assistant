package com.university.docassistant.dto;

import java.time.LocalDateTime;

/**
 * One of the student's own tasks the answer cites as [задача N]: {@code number} is N, its place in the prompt's
 * [МОИ ЗАДАЧИ] block. {@code state} is the state the prompt named: OVERDUE, NOT_STARTED, IN_PROGRESS, SUBMITTED,
 * LATE (handed in late), GRADED, COMPLETED (a legacy task), BEFORE_ENROLLMENT or CLOSED - the same rules as
 * "Мои задачи".
 */
public record AnswerTaskDto(
        int number,
        Long taskId,
        Long courseId,
        String courseTitle,
        String title,
        LocalDateTime deadline,
        String state
) {
}
