package com.university.docassistant.service;

import com.university.docassistant.dto.GradingDtos.GradeRequest;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskRepository.GradeTarget;
import com.university.docassistant.repository.TaskRepository.GradingRow;
import com.university.docassistant.repository.TaskSubmissionRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-11: only the course's teacher or ADMIN grades; the score stays within 0..max with one decimal; work that was
 * never handed in can be graded; re-grading is audited with the old and the new score; taking the grade back returns
 * the task to SUBMITTED or PENDING.
 */
class GradingServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 18, 0);
    private static final LocalDateTime HANDED_IN = LocalDateTime.of(2026, 10, 20, 12, 0);

    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskSubmissionRepository submissions = mock(TaskSubmissionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final NotificationEvents notifications = mock(NotificationEvents.class);
    private final AccessControlService access = new AccessControlService(users, courses, mock(DocumentRepository.class),
            mock(EnrollmentRepository.class), audit);
    private final GradingService service = new GradingService(tasks, submissions, mock(AssignmentRepository.class),
            access, audit, notifications, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));

    private final User teacher = user(2L, Role.ROLE_TEACHER, "t@uni.kz");
    private final User otherTeacher = user(3L, Role.ROLE_TEACHER, "other@uni.kz");
    private final User admin = user(4L, Role.ROLE_ADMIN, "dean@uni.kz");
    private final User student = user(1L, Role.ROLE_STUDENT, "s@uni.kz");
    private final Course course = Course.builder().id(10L).title("Algorithms").teacher(teacher).build();

    @BeforeEach
    void setUp() {
        for (User user : List.of(teacher, otherTeacher, admin, student)) {
            when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        }
        when(courses.findWithTeacherById(10L)).thenReturn(Optional.of(course));
        when(tasks.findGradingRowById(7L)).thenReturn(Optional.of(new GradingRow(7L, 1L, "Студент", "s@uni.kz",
                TaskStatus.GRADED, HANDED_IN, false, BigDecimal.TEN, null, NOW)));
        when(submissions.findCurrentViewsByTaskIdIn(any())).thenReturn(List.of());
        handedIn(TaskStatus.SUBMITTED, null, 10);
    }

    @Test
    void scoreStaysWithinZeroAndTheMaxWithOneDecimal() {
        assertThat(GradingService.validScore(new BigDecimal("0"), 10)).isEqualByComparingTo("0");
        assertThat(GradingService.validScore(new BigDecimal("10"), 10)).isEqualByComparingTo("10");
        assertThat(GradingService.validScore(new BigDecimal("8.50"), 10)).isEqualTo(new BigDecimal("8.5"));
        assertThat(GradingService.validScore(new BigDecimal("1000"), null)).as("no max score: up to 1000")
                .isEqualByComparingTo("1000");
        for (String bad : List.of("-1", "10.5", "8.55")) {
            assertInvalidScore(() -> GradingService.validScore(new BigDecimal(bad), 10));
        }
        assertInvalidScore(() -> GradingService.validScore(new BigDecimal("1000.1"), null));
        assertInvalidScore(() -> GradingService.validScore(null, 10));
    }

    @Test
    void aScoreAboveTheMaxIsRefusedAndNothingIsSaved() {
        assertThatThrownBy(() -> service.grade(7L, new GradeRequest(new BigDecimal("11"), null), "t@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.GRADE_SCORE_INVALID);
                    assertThat(ex.getMessage()).contains("от 0 до 10");
                });
        verify(tasks, never()).grade(anyLong(), any(), any(), any(), any());
    }

    @Test
    void theTeacherGradesHandedInWorkAndTheStudentIsToldAfterCommit() {
        service.grade(7L, new GradeRequest(new BigDecimal("8.5"), "  Хорошо, но без выводов  "), "t@uni.kz");

        verify(tasks).lockById(7L);
        verify(tasks).grade(7L, new BigDecimal("8.5"), "Хорошо, но без выводов", NOW, teacher);
        verify(audit).record(eq("t@uni.kz"), eq(AuditService.TASK_GRADED), contains("— -> 8.5"));
        verify(notifications).taskGraded(7L);
    }

    @Test
    void workThatWasNeverHandedInCanBeGradedZero() {
        handedIn(TaskStatus.PENDING, null, 10);
        when(tasks.findGradeTargetById(7L)).thenReturn(Optional.of(
                new GradeTarget(7L, 10L, 3L, TaskStatus.PENDING, null, null, 10, "s@uni.kz")));

        service.grade(7L, new GradeRequest(BigDecimal.ZERO, "Не сдано"), "t@uni.kz");

        verify(tasks).grade(7L, BigDecimal.ZERO, "Не сдано", NOW, teacher);
    }

    @Test
    void reGradingIsAuditedWithTheOldAndTheNewScore() {
        handedIn(TaskStatus.GRADED, new BigDecimal("7.0"), 10);

        service.grade(7L, new GradeRequest(new BigDecimal("9"), null), "dean@uni.kz");

        verify(tasks).grade(7L, new BigDecimal("9"), null, NOW, admin);
        verify(audit).record(eq("dean@uni.kz"), eq(AuditService.TASK_GRADED), contains("7 -> 9"));
    }

    @Test
    void aStudentCannotGrade() {
        assertForbidden(() -> service.grade(7L, new GradeRequest(BigDecimal.TEN, null), "s@uni.kz"));
        assertForbidden(() -> service.ungrade(7L, "s@uni.kz"));
        assertThat(Permission.granted(Role.ROLE_STUDENT, Permission.GRADE_WORK)).isFalse();
    }

    @Test
    void anotherCoursesTeacherCannotGrade() {
        assertForbidden(() -> service.grade(7L, new GradeRequest(BigDecimal.TEN, null), "other@uni.kz"));
        verify(audit).record(eq("other@uni.kz"), eq(AuditService.ACCESS_DENIED), any());
    }

    @Test
    void legacyTasksAreNotGraded() {
        when(tasks.findGradeTargetById(8L)).thenReturn(Optional.of(
                new GradeTarget(8L, null, null, TaskStatus.COMPLETED, null, null, null, "s@uni.kz")));

        assertThatThrownBy(() -> service.grade(8L, new GradeRequest(BigDecimal.ONE, null), "t@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.TASK_NOT_GRADABLE));
    }

    @Test
    void takingTheGradeBackReturnsHandedInWorkToSubmitted() {
        handedIn(TaskStatus.GRADED, BigDecimal.TEN, 10);

        service.ungrade(7L, "t@uni.kz");

        verify(tasks).clearGrade(7L, TaskStatus.SUBMITTED);
        verify(audit).record(eq("t@uni.kz"), eq(AuditService.TASK_UNGRADED), contains("back to SUBMITTED"));
    }

    @Test
    void takingTheGradeBackWithoutAHandInReturnsTheTaskToPending() {
        when(tasks.findGradeTargetById(7L)).thenReturn(Optional.of(
                new GradeTarget(7L, 10L, 3L, TaskStatus.GRADED, null, BigDecimal.ZERO, 10, "s@uni.kz")));

        service.ungrade(7L, "t@uni.kz");

        verify(tasks).clearGrade(7L, TaskStatus.PENDING);
    }

    @Test
    void takingBackAGradeThatIsNotThereChangesNothing() {
        service.ungrade(7L, "t@uni.kz");

        verify(tasks, never()).clearGrade(anyLong(), any());
        verify(audit, never()).record(any(), eq(AuditService.TASK_UNGRADED), any());
    }

    /** Task 7 of assignment 3 in course 10, handed in (unless PENDING) and in {@code status}. */
    private void handedIn(TaskStatus status, BigDecimal score, Integer maxScore) {
        when(tasks.findGradeTargetById(7L)).thenReturn(Optional.of(new GradeTarget(7L, 10L, 3L, status,
                status == TaskStatus.PENDING ? null : HANDED_IN, score, maxScore, "s@uni.kz")));
    }

    private static void assertInvalidScore(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getCode()).isEqualTo(ErrorCodes.GRADE_SCORE_INVALID);
        });
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(tasks, never()).grade(anyLong(), any(), any(), any(), any());
        verify(tasks, never()).clearGrade(anyLong(), any());
    }

    private static User user(Long id, Role role, String email) {
        return User.builder().id(id).email(email).role(role).build();
    }
}
