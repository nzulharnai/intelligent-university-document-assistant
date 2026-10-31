package com.university.docassistant.service;

import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.NotificationType;
import com.university.docassistant.repository.EnrollmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Things the course's students should be told about, sent as notifications (US-13). Called inside the transaction
 * that made the change: the notifications are saved with it, their e-mails go out after it commits.
 */
@Component
@RequiredArgsConstructor
public class AssignmentEvents {

    /** Where a student sees their tasks. */
    static final String TASKS_LINK = "/dashboard";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final NotificationService notificationService;
    private final EnrollmentRepository enrollmentRepository;

    /** Assignments of the course became ACTIVE in one action: one notification per student, listing the titles. */
    public void published(Long courseId, String courseTitle, List<String> titles) {
        if (titles.isEmpty()) {
            return;
        }
        String title = titles.size() == 1 ? "Новое задание: " + titles.get(0) : "Новые задания: " + titles.size();
        String body = "Курс «" + courseTitle + "». "
                + (titles.size() == 1 ? "Преподаватель опубликовал задание «" + titles.get(0) + "»."
                        : "Преподаватель опубликовал задания: " + String.join(", ", titles.stream().map(t -> "«" + t + "»").toList()) + ".");
        notificationService.notify(enrollmentRepository.findStudentIdsByCourseId(courseId),
                NotificationType.ASSIGNMENT_PUBLISHED, title, body, TASKS_LINK);
    }

    /** The deadline of an ACTIVE assignment changed; {@code previous} or {@code current} may be null (no deadline). */
    public void deadlineChanged(Assignment assignment, LocalDateTime previous, LocalDateTime current) {
        notificationService.notify(enrollmentRepository.findStudentIdsByCourseId(assignment.getCourse().getId()),
                NotificationType.DEADLINE_CHANGED, "Срок изменён: " + assignment.getTitle(),
                "Курс «" + assignment.getCourse().getTitle() + "». Срок задания «" + assignment.getTitle()
                        + "» изменён: " + dateText(previous) + " → " + dateText(current) + ".",
                TASKS_LINK);
    }

    static String dateText(LocalDateTime value) {
        return value == null ? "без срока" : DATE_TIME.format(value);
    }
}
