package com.university.docassistant.service;

import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Notification;
import com.university.docassistant.entity.NotificationType;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.EmailSendException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.NotificationRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskRepository.GradedNotice;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.repository.UserRepository.MailRecipient;
import com.university.docassistant.security.AccessControlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-13: one notification row per user, saved with the caller's transaction; e-mails only to users who kept them on
 * and only after commit, on the mail executor; a failed or refused e-mail never reaches the caller. Users read only
 * their own notifications. The hooks address the right users.
 */
class NotificationServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 18, 0);

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final EmailService email = mock(EmailService.class);
    private final AccessControlService access = new AccessControlService(users, mock(CourseRepository.class),
            mock(DocumentRepository.class), enrollments, mock(AuditService.class));
    private final User student = User.builder().id(1L).email("s@uni.kz").role(Role.ROLE_STUDENT).build();

    private NotificationService service(TaskExecutor executor) {
        return new NotificationService(notifications, users, access, email, executor,
                Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE), "https://unidocs.example/");
    }

    @BeforeEach
    void setUp() {
        when(users.findByEmail("s@uni.kz")).thenReturn(Optional.of(student));
        when(users.getReferenceById(anyLong())).thenAnswer(invocation -> User.builder().id(invocation.getArgument(0)).build());
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void oneRowPerUserNowAndTheEmailsOnlyAfterCommitToThoseWhoWantThem() {
        when(users.findMailRecipients(List.of(1L, 2L, 3L))).thenReturn(List.of(new MailRecipient(1L, "s@uni.kz", "Студент")));

        service(new SyncTaskExecutor()).notify(List.of(1L, 2L, 3L, 2L), NotificationType.ASSIGNMENT_PUBLISHED,
                "Новое задание: Lab 1", "Курс «Алгоритмы».", "/dashboard");

        verify(notifications).saveAll(argThat(rows -> {
            List<Notification> list = new ArrayList<>();
            rows.forEach(list::add);
            return list.size() == 3 && list.stream().allMatch(row -> row.getCreatedAt().equals(NOW)
                    && row.getType() == NotificationType.ASSIGNMENT_PUBLISHED && row.getReadAt() == null);
        }));
        verify(email, never()).sendNotification(any(), any(), any(), any(), any());

        commit();

        // Users 2 and 3 turned e-mails off: the query leaves them out, so only user 1 is mailed.
        verify(email).sendNotification("s@uni.kz", "Студент", "Новое задание: Lab 1", "Курс «Алгоритмы».",
                "https://unidocs.example/dashboard");
    }

    @Test
    void nobodyWantsEmailsNothingIsQueued() {
        when(users.findMailRecipients(anyList())).thenReturn(List.of());

        service(new SyncTaskExecutor()).notify(List.of(1L), NotificationType.TASK_GRADED, "t", "b", "/dashboard");

        assertThat(TransactionSynchronizationManager.getSynchronizations()).as("nothing waits for the commit").isEmpty();
        verify(notifications).saveAll(any());
        verify(email, never()).sendNotification(any(), any(), any(), any(), any());
    }

    @Test
    void aFailedEmailIsOnlyLoggedAndTheOthersStillGo() {
        when(users.findMailRecipients(anyList())).thenReturn(List.of(new MailRecipient(1L, "a@uni.kz", "A"),
                new MailRecipient(2L, "b@uni.kz", "B")));
        doThrow(new EmailSendException("smtp down", null))
                .when(email).sendNotification(eq("a@uni.kz"), any(), any(), any(), any());

        service(new SyncTaskExecutor()).notify(List.of(1L, 2L), NotificationType.TASK_GRADED, "t", "b", "/dashboard");

        assertThatNoException().isThrownBy(this::commit);
        verify(email).sendNotification(eq("b@uni.kz"), any(), any(), any(), any());
    }

    @Test
    void aFullMailQueueIsOnlyLogged() {
        when(users.findMailRecipients(anyList())).thenReturn(List.of(new MailRecipient(1L, "a@uni.kz", "A")));
        TaskExecutor full = task -> {
            throw new TaskRejectedException("queue full");
        };

        service(full).notify(List.of(1L), NotificationType.TASK_GRADED, "t", "b", "/dashboard");

        assertThatNoException().isThrownBy(this::commit);
    }

    @Test
    void someoneElsesNotificationIsNotFound() {
        when(notifications.findViewByIdAndUserId(5L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(new SyncTaskExecutor()).markRead(5L, "s@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.NOTIFICATION_NOT_FOUND);
                });
        // The update itself is limited to the user's own rows.
        verify(notifications).markRead(5L, 1L, NOW);
    }

    @Test
    void publishingNotifiesTheCoursesStudentsOnceWithAllTitles() {
        NotificationService notificationService = mock(NotificationService.class);
        when(enrollments.findStudentIdsByCourseId(10L)).thenReturn(List.of(1L, 2L));

        new AssignmentEvents(notificationService, enrollments).published(10L, "Алгоритмы", List.of("Lab 1", "Lab 2"));

        verify(notificationService).notify(eq(List.of(1L, 2L)), eq(NotificationType.ASSIGNMENT_PUBLISHED),
                eq("Новые задания: 2"), argThat(body -> body.contains("«Lab 1»") && body.contains("«Lab 2»")),
                eq("/dashboard"));
    }

    @Test
    void aMovedDeadlineTellsTheOldAndTheNewDate() {
        NotificationService notificationService = mock(NotificationService.class);
        when(enrollments.findStudentIdsByCourseId(10L)).thenReturn(List.of(1L));
        Assignment lab = Assignment.builder().id(3L).title("Lab 1")
                .course(Course.builder().id(10L).title("Алгоритмы").build()).build();

        new AssignmentEvents(notificationService, enrollments).deadlineChanged(lab,
                LocalDateTime.of(2026, 10, 20, 23, 59), LocalDateTime.of(2026, 10, 25, 23, 59));

        verify(notificationService).notify(eq(List.of(1L)), eq(NotificationType.DEADLINE_CHANGED), anyString(),
                contains("20.10.2026 23:59 → 25.10.2026 23:59"), eq("/dashboard"));
    }

    @Test
    void aGradeIsToldToTheTasksStudentWithTheScore() {
        NotificationService notificationService = mock(NotificationService.class);
        TaskRepository tasks = mock(TaskRepository.class);
        when(tasks.findGradedNoticeById(7L)).thenReturn(Optional.of(
                new GradedNotice(1L, "Lab 1", new BigDecimal("8.50"), 10, "ALG")));

        new NotificationEvents(notificationService, tasks).taskGraded(7L);

        verify(notificationService).notify(eq(List.of(1L)), eq(NotificationType.TASK_GRADED), eq("Работа оценена: Lab 1"),
                contains("Оценка: 8.5 из 10"), eq("/dashboard"));
    }

    /** What the transaction manager does after a commit: run the registered callbacks. */
    private void commit() {
        List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        callbacks.forEach(TransactionSynchronization::afterCommit);
    }
}
