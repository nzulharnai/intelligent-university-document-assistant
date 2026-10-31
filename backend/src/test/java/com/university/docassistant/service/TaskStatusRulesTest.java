package com.university.docassistant.service;

import com.university.docassistant.dto.TaskResponse;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskRepository.TaskAccess;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-10: on an assignment task the student moves only between PENDING and IN_PROGRESS; SUBMITTED and GRADED are the
 * system's. Legacy tasks without an assignment keep their free changes, COMPLETED included.
 */
class TaskStatusRulesTest {

    private static final String EMAIL = "student@uni.kz";
    private static final long ASSIGNMENT_TASK = 7L;
    private static final long LEGACY_TASK = 8L;

    private final TaskRepository tasks = mock(TaskRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessControlService access = new AccessControlService(users, mock(CourseRepository.class),
            mock(DocumentRepository.class), enrollments, audit);
    private final ZoneId zone = ZoneId.of("Asia/Almaty");
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 24, 15, 30);
    private final TaskService service = new TaskService(tasks, access, Clock.fixed(now.atZone(zone).toInstant(), zone));

    private final User student = User.builder().id(1L).email(EMAIL).role(Role.ROLE_STUDENT).build();

    @BeforeEach
    void setUp() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(student));
        when(tasks.findAccessById(ASSIGNMENT_TASK)).thenReturn(Optional.of(new TaskAccess(ASSIGNMENT_TASK, 1L, 10L, 3L)));
        when(tasks.findAccessById(LEGACY_TASK)).thenReturn(Optional.of(new TaskAccess(LEGACY_TASK, 1L, null, null)));
        when(enrollments.existsByStudentIdAndCourseId(1L, 10L)).thenReturn(true);
        when(tasks.findViewById(anyLong())).thenAnswer(invocation -> Optional.of(view(invocation.getArgument(0))));
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"PENDING", "IN_PROGRESS"})
    void studentMovesAnAssignmentTaskBetweenPendingAndInProgress(TaskStatus status) {
        when(tasks.updateStudentStatus(ASSIGNMENT_TASK, status)).thenReturn(1);

        service.updateStatus(ASSIGNMENT_TASK, status, EMAIL);

        verify(tasks).updateStudentStatus(ASSIGNMENT_TASK, status);
        verify(tasks, never()).updateStatus(anyLong(), any());
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"SUBMITTED", "GRADED", "COMPLETED"})
    void studentCannotCompleteSubmitOrGradeAnAssignmentTaskByHand(TaskStatus status) {
        assertSystemOnly(() -> service.updateStatus(ASSIGNMENT_TASK, status, EMAIL));
        verify(tasks, never()).updateStudentStatus(anyLong(), any());
        verify(tasks, never()).updateStatus(anyLong(), any());
    }

    @Test
    void aSubmittedOrGradedTaskCannotBeMovedBack() {
        // The conditional update finds no PENDING/IN_PROGRESS row: the task is already SUBMITTED or GRADED.
        when(tasks.updateStudentStatus(ASSIGNMENT_TASK, TaskStatus.IN_PROGRESS)).thenReturn(0);

        assertSystemOnly(() -> service.updateStatus(ASSIGNMENT_TASK, TaskStatus.IN_PROGRESS, EMAIL));
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"PENDING", "IN_PROGRESS", "COMPLETED"})
    void legacyTasksKeepTheirFreeStatusChanges(TaskStatus status) {
        TaskResponse result = service.updateStatus(LEGACY_TASK, status, EMAIL);

        verify(tasks).updateStatus(LEGACY_TASK, status);
        verify(tasks, never()).updateStudentStatus(anyLong(), any());
        assertThat(result.id()).isEqualTo(LEGACY_TASK);
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"SUBMITTED", "GRADED"})
    void legacyTasksCannotBeSubmittedOrGradedByHandEither(TaskStatus status) {
        assertSystemOnly(() -> service.updateStatus(LEGACY_TASK, status, EMAIL));
        verify(tasks, never()).updateStatus(anyLong(), any());
    }

    @Test
    void someoneElsesTaskIs403AndAudited() {
        when(tasks.findAccessById(9L)).thenReturn(Optional.of(new TaskAccess(9L, 99L, 10L, 3L)));

        assertThatThrownBy(() -> service.updateStatus(9L, TaskStatus.IN_PROGRESS, EMAIL))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.TASK_NOT_OWN);
                });
        verify(audit).record(eq(EMAIL), eq(AuditService.ACCESS_DENIED), any());
        verify(tasks, never()).updateStudentStatus(anyLong(), any());
    }

    @Test
    void upcomingDeadlinesStartAtTheClocksNow() {
        when(tasks.findUpcomingViews(eq(1L), any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

        service.upcoming(EMAIL, 5);

        verify(tasks).findUpcomingViews(eq(1L), eq(now), any());
    }

    private static void assertSystemOnly(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getCode()).isEqualTo(ErrorCodes.TASK_STATUS_SYSTEM_ONLY);
        });
    }

    private static TaskResponse view(Long id) {
        return new TaskResponse(id, "Lab 1", null, null, TaskStatus.IN_PROGRESS, null, null, null, null, null, null,
                null, 0L, null, false, null, null, null, null, null);
    }
}
