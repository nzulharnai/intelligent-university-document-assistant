package com.university.docassistant.dto;

import java.time.LocalDateTime;

/**
 * An assignment the answer cites as [задание N]: {@code number} is N, its place in the prompt's
 * [АКТУАЛЬНЫЕ ЗАДАНИЯ КУРСА] block. {@code deadline} is the current one from the assignment, null without a deadline.
 */
public record AnswerAssignmentDto(
        int number,
        Long assignmentId,
        Long courseId,
        String courseTitle,
        String title,
        LocalDateTime deadline
) {
}
