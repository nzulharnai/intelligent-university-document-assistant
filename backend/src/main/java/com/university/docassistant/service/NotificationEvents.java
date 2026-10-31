package com.university.docassistant.service;

import com.university.docassistant.entity.NotificationType;
import com.university.docassistant.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Things a student should be told about their own work, sent as notifications (US-13). Called inside the transaction
 * that made the change: the notification is saved with it, its e-mail goes out after it commits.
 */
@Component
@RequiredArgsConstructor
public class NotificationEvents {

    private final NotificationService notificationService;
    private final TaskRepository taskRepository;

    /** The teacher graded (or re-graded) the task; its student hears the score. */
    public void taskGraded(Long taskId) {
        taskRepository.findGradedNoticeById(taskId).ifPresent(notice -> notificationService.notify(
                List.of(notice.userId()), NotificationType.TASK_GRADED,
                "Работа оценена: " + notice.title(),
                (notice.courseCode() == null ? "" : notice.courseCode() + ". ") + "Оценка: " + scoreText(notice.score(), notice.maxScore())
                        + ". Отзыв преподавателя — в «Моих задачах».",
                AssignmentEvents.TASKS_LINK));
    }

    static String scoreText(BigDecimal score, Integer maxScore) {
        String value = score == null ? "—" : score.stripTrailingZeros().toPlainString();
        return maxScore == null ? value : value + " из " + maxScore;
    }
}
