package com.university.docassistant.service;

import com.university.docassistant.entity.NotificationType;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskRepository.ReminderNotice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * US-13: "Срок сдачи «…» — завтра в 23:59". Tasks with nothing handed in whose deadline is within the next
 * {@code app.notifications.reminder-hours} (default 24) get one DEADLINE_REMINDER_24H each: only of ACTIVE
 * assignments, only for students still enrolled. Each task's reminder is claimed with an atomic update first
 * (tasks.reminder_sent_at), so two runs or two instances never send it twice; a deadline moved later clears the claim
 * (TaskRepository.syncWithAssignments). Works in batches, each its own short transaction, never one big one.
 */
@Slf4j
@Service
public class DeadlineReminderService {

    static final int BATCH_SIZE = 100;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy 'в' HH:mm");

    private final TaskRepository taskRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int reminderHours;

    public DeadlineReminderService(TaskRepository taskRepository, NotificationService notificationService,
                                   AuditService auditService, PlatformTransactionManager transactionManager,
                                   Clock clock, @Value("${app.notifications.reminder-hours:24}") int reminderHours) {
        this.taskRepository = taskRepository;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.reminderHours = reminderHours;
    }

    /** Sends every due reminder; returns how many were sent. */
    public int sendDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime until = now.plusHours(reminderHours);
        int sent = 0;
        long afterId = 0;
        while (true) {
            List<Long> ids = taskRepository.findReminderDueIds(afterId, now, until, PageRequest.of(0, BATCH_SIZE));
            if (ids.isEmpty()) {
                break;
            }
            afterId = ids.get(ids.size() - 1);
            Integer batchSent = transactionTemplate.execute(status -> sendBatch(ids, now, until));
            sent += batchSent == null ? 0 : batchSent;
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }
        if (sent > 0) {
            log.info("Deadline reminders: {} sent", sent);
        } else {
            log.debug("Deadline reminders: 0 sent");
        }
        return sent;
    }

    /** ADMIN runs the reminders now (a demo, or after an outage); audited. Same rules, same once-per-task claim. */
    public int runNow(String adminEmail) {
        int sent = sendDue();
        auditService.record(adminEmail, AuditService.REMINDERS_RUN, sent + " reminder(s) sent");
        return sent;
    }

    /** One batch in one transaction: claim each reminder, then notify the students whose claims succeeded. */
    private int sendBatch(List<Long> ids, LocalDateTime now, LocalDateTime until) {
        List<Long> claimed = new ArrayList<>();
        for (Long id : ids) {
            if (taskRepository.claimReminder(id, now, until) == 1) {
                claimed.add(id);
            }
        }
        if (claimed.isEmpty()) {
            return 0;
        }
        for (ReminderNotice notice : taskRepository.findReminderNotices(claimed)) {
            notificationService.notify(List.of(notice.userId()), NotificationType.DEADLINE_REMINDER_24H,
                    "Срок сдачи «" + notice.title() + "» — " + whenText(notice.deadline(), now),
                    (notice.courseCode() == null ? "" : notice.courseCode() + ". ")
                            + "Работа ещё не сдана. Сдайте её в «Моих задачах», пока срок не прошёл.",
                    AssignmentEvents.TASKS_LINK);
        }
        return claimed.size();
    }

    /** "сегодня в 18:00", "завтра в 23:59", otherwise the date: as the student reads it on the day of {@code now}. */
    static String whenText(LocalDateTime deadline, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (deadline.toLocalDate().equals(today)) {
            return "сегодня в " + TIME.format(deadline);
        }
        if (deadline.toLocalDate().equals(today.plusDays(1))) {
            return "завтра в " + TIME.format(deadline);
        }
        return DATE_TIME.format(deadline);
    }
}
