package com.university.docassistant.service;

import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.TaskSubmission;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskRepository.SubmitRules;
import com.university.docassistant.repository.TaskRepository.TaskAccess;
import com.university.docassistant.repository.TaskSubmissionRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import com.university.docassistant.service.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Students upload only as a submission to their own task in a course they are enrolled in (US-10): accepted while
 * the assignment is ACTIVE and the task not GRADED, after the deadline only when late work is allowed; every hand-in
 * is a new version.
 */
class SubmissionServiceTest {

    private final TaskSubmissionRepository submissions = mock(TaskSubmissionRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final StorageService storage = mock(StorageService.class);
    /** 2026-10-24 15:30 in the app time zone. */
    static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 15, 30);
    static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);

    private final AuditService audit = mock(AuditService.class);
    private final SubmissionService service = serviceAt(CLOCK);

    private final User student = user(1L, Role.ROLE_STUDENT, "s@uni.kz");
    private final User teacher = user(2L, Role.ROLE_TEACHER, "t@uni.kz");
    private final Course course = Course.builder().id(10L).title("Algorithms").build();
    private final List<MultipartFile> files = List.of(
            new MockMultipartFile("files", "solution.pdf", "application/pdf", new byte[]{1, 2, 3}));

    @Test
    void teacherCannotSubmitAndNothingIsRead() {
        when(access.currentUser("t@uni.kz")).thenReturn(teacher);

        assertStatus(() -> service.submit(7L, files, "t@uni.kz"), HttpStatus.FORBIDDEN);
        verify(tasks, never()).findAccessById(any());
        verify(storage, never()).store(any(byte[].class));
    }

    @Test
    void studentCannotSubmitToSomeoneElsesTask() {
        when(access.currentUser("s@uni.kz")).thenReturn(student);
        when(tasks.findAccessById(7L)).thenReturn(Optional.of(new TaskAccess(7L, 99L, 10L, 3L)));

        assertStatus(() -> service.submit(7L, files, "s@uni.kz"), HttpStatus.FORBIDDEN);
        verify(storage, never()).store(any(byte[].class));
    }

    @Test
    void studentWhoLeftTheCourseCannotSubmit() {
        when(access.currentUser("s@uni.kz")).thenReturn(student);
        when(tasks.findAccessById(7L)).thenReturn(Optional.of(new TaskAccess(7L, 1L, 10L, 3L)));
        when(courses.findById(10L)).thenReturn(Optional.of(course));
        when(access.isMember(student, course)).thenReturn(false);

        assertStatus(() -> service.submit(7L, files, "s@uni.kz"), HttpStatus.FORBIDDEN);
        verify(submissions, never()).saveAll(anyList());
    }

    @Test
    void unsupportedFileRejectsTheWholeSubmission() {
        acceptingTask(rules(TaskStatus.IN_PROGRESS, NOW.plusDays(1), true));
        List<MultipartFile> withScript = List.of(files.get(0),
                new MockMultipartFile("files", "run.exe", "application/octet-stream", new byte[]{1}));

        assertStatus(() -> service.submit(7L, withScript, "s@uni.kz"), HttpStatus.BAD_REQUEST);
        verify(storage, never()).store(any(byte[].class));
    }

    @Test
    void onTimeSubmissionMakesTheTaskSubmittedAtTheClocksTime() {
        acceptingTask(rules(TaskStatus.IN_PROGRESS, NOW.plusHours(1), false));

        service.submit(7L, files, "s@uni.kz");

        verify(storage).store(any(byte[].class));
        verify(submissions).saveAll(argThat(rows -> first(rows).getSubmittedAt().equals(NOW) && first(rows).getVersion() == 1));
        verify(tasks).lockById(7L);
        verify(tasks).markSubmitted(7L, NOW, false);
        verify(audit).record(eq("s@uni.kz"), eq(AuditService.TASK_SUBMITTED), argThat(details -> !details.contains("late")));
    }

    @Test
    void lateSubmissionIsAcceptedAndMarkedWhenLateWorkIsAllowed() {
        acceptingTask(rules(TaskStatus.PENDING, NOW.minusHours(1), true));

        service.submit(7L, files, "s@uni.kz");

        verify(tasks).markSubmitted(7L, NOW, true);
        verify(audit).record(eq("s@uni.kz"), eq(AuditService.TASK_SUBMITTED), contains(", late"));
    }

    @Test
    void lateSubmissionIsRefusedWithTheDeadlineWhenLateWorkIsForbidden() {
        acceptingTask(rules(TaskStatus.IN_PROGRESS, LocalDateTime.of(2026, 10, 20, 23, 59), false));

        assertThatThrownBy(() -> service.submit(7L, files, "s@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.SUBMISSION_DEADLINE_PASSED);
                    assertThat(ex.getMessage()).contains("20.10.2026 23:59");
                });
        verify(storage, never()).store(any(byte[].class));
    }

    @Test
    void closedAssignmentTakesNoSubmission() {
        acceptingTask(rules(TaskStatus.IN_PROGRESS, NOW.plusDays(1), true, AssignmentStatus.CLOSED));

        assertCode(() -> service.submit(7L, files, "s@uni.kz"), HttpStatus.CONFLICT, ErrorCodes.ASSIGNMENT_CLOSED);
        verify(storage, never()).store(any(byte[].class));
    }

    @Test
    void gradedWorkCannotBeHandedInAgain() {
        acceptingTask(rules(TaskStatus.GRADED, NOW.plusDays(1), true));

        assertCode(() -> service.submit(7L, files, "s@uni.kz"), HttpStatus.BAD_REQUEST, ErrorCodes.SUBMISSION_ALREADY_GRADED);
        verify(storage, never()).store(any(byte[].class));
    }

    @Test
    void gradedMeanwhileIsCaughtUnderTheLockAndTheStoredFilesAreRemoved() {
        acceptingTask(rules(TaskStatus.SUBMITTED, NOW.plusDays(1), true));
        // Passes the early check, then the teacher grades the task before the row lock is taken.
        when(tasks.findSubmitRulesById(7L)).thenReturn(Optional.of(rules(TaskStatus.SUBMITTED, NOW.plusDays(1), true)),
                Optional.of(rules(TaskStatus.GRADED, NOW.plusDays(1), true)));

        assertCode(() -> service.submit(7L, files, "s@uni.kz"), HttpStatus.BAD_REQUEST, ErrorCodes.SUBMISSION_ALREADY_GRADED);
        verify(submissions, never()).saveAll(anyList());
        verify(storage).delete("stored-1");
    }

    @Test
    void resubmissionIsTheNextVersionAndKeepsTheEarlierOnes() {
        acceptingTask(rules(TaskStatus.SUBMITTED, NOW.plusDays(1), true));
        when(submissions.findMaxVersion(7L)).thenReturn(1);

        service.submit(7L, files, "s@uni.kz");

        verify(submissions).saveAll(argThat(rows -> first(rows).getVersion() == 2));
        verify(submissions, never()).deleteAll(any());
        verify(submissions, never()).deleteById(any());
        verify(storage, never()).delete(any());
        verify(audit).record(eq("s@uni.kz"), eq(AuditService.TASK_SUBMITTED), contains("version 2"));
    }

    @Test
    void theDeadlineMinuteItselfIsOnTimeAndTheNextDayIsLate() {
        LocalDateTime deadline = LocalDateTime.of(2026, 10, 20, 23, 59, 59);
        acceptingTask(rules(TaskStatus.IN_PROGRESS, deadline, false));

        serviceAt(fixedAt(deadline)).submit(7L, files, "s@uni.kz");
        verify(tasks).markSubmitted(7L, deadline, false);

        assertCode(() -> serviceAt(fixedAt(LocalDateTime.of(2026, 10, 21, 0, 0, 0))).submit(7L, files, "s@uni.kz"),
                HttpStatus.BAD_REQUEST, ErrorCodes.SUBMISSION_DEADLINE_PASSED);
        assertThat(SubmissionService.isLate(deadline, deadline)).isFalse();
        assertThat(SubmissionService.isLate(deadline, LocalDateTime.of(2026, 10, 21, 0, 0, 0))).isTrue();
        assertThat(SubmissionService.isLate(null, NOW)).as("no deadline, never late").isFalse();
    }

    @Test
    void otherStudentCannotReadSubmissionsOfATask() {
        User other = user(3L, Role.ROLE_STUDENT, "o@uni.kz");
        when(access.currentUser("o@uni.kz")).thenReturn(other);
        when(tasks.findAccessById(7L)).thenReturn(Optional.of(new TaskAccess(7L, 1L, 10L, 3L)));
        when(access.authorizeCourse(other, 10L, Permission.MANAGE_ASSIGNMENTS))
                .thenThrow(ApiException.forbidden(ErrorCodes.COURSE_NOT_ENROLLED, "You are not enrolled in this course"));

        assertStatus(() -> service.listForTask(7L, "o@uni.kz"), HttpStatus.FORBIDDEN);
        verify(submissions, never()).findViewsByTaskId(any());
    }

    @Test
    void onlyStudentsHoldTheSubmitPermission() {
        assertThat(Permission.granted(Role.ROLE_STUDENT, Permission.SUBMIT_TASK)).isTrue();
        assertThat(Permission.granted(Role.ROLE_TEACHER, Permission.SUBMIT_TASK)).isFalse();
        assertThat(Permission.granted(Role.ROLE_ADMIN, Permission.SUBMIT_TASK)).isFalse();
        assertThat(Permission.granted(Role.ROLE_STUDENT, Permission.UPLOAD_DOCUMENT)).isFalse();
    }

    /** The student's own task 7 of assignment 3 in course 10, enrolled, with these acceptance rules. */
    private void acceptingTask(SubmitRules rules) {
        when(access.currentUser("s@uni.kz")).thenReturn(student);
        when(tasks.findAccessById(7L)).thenReturn(Optional.of(new TaskAccess(7L, 1L, 10L, 3L)));
        when(courses.findById(10L)).thenReturn(Optional.of(course));
        when(access.isMember(student, course)).thenReturn(true);
        when(tasks.findSubmitRulesById(7L)).thenReturn(Optional.of(rules));
        when(storage.store(any(byte[].class))).thenReturn("stored-1");
        when(submissions.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tasks.markSubmitted(anyLong(), any(), anyBoolean())).thenReturn(1);
    }

    private static SubmitRules rules(TaskStatus status, LocalDateTime deadline, boolean allowLate) {
        return rules(status, deadline, allowLate, AssignmentStatus.ACTIVE);
    }

    private static SubmitRules rules(TaskStatus status, LocalDateTime deadline, boolean allowLate,
                                     AssignmentStatus assignmentStatus) {
        return new SubmitRules(status, deadline, allowLate, assignmentStatus);
    }

    private SubmissionService serviceAt(Clock clock) {
        return new SubmissionService(submissions, tasks, mock(AssignmentRepository.class), courses, access, storage,
                audit, new TransactionTemplate(mock(PlatformTransactionManager.class)), clock);
    }

    private static Clock fixedAt(LocalDateTime time) {
        return Clock.fixed(time.atZone(ZONE).toInstant(), ZONE);
    }

    private static TaskSubmission first(Iterable<TaskSubmission> rows) {
        return rows.iterator().next();
    }

    private static void assertCode(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(status));
    }

    private static User user(Long id, Role role, String email) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setEmail(email);
        return user;
    }
}
