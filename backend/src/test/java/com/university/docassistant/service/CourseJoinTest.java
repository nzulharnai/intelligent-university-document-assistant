package com.university.docassistant.service;

import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.JoinAttemptLimiter;
import com.university.docassistant.util.InviteCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Students join only with the secret invitation code; course ids are no longer enough. */
class CourseJoinTest {

    private final User student = User.builder().id(1L).email("student@uni.kz").role(Role.ROLE_STUDENT).build();
    private final User teacher = User.builder().id(2L).email("teacher@uni.kz").role(Role.ROLE_TEACHER).build();
    private final Course course = Course.builder().id(345L).code("INF345").title("Algorithms").teacher(teacher)
            .inviteCode(InviteCodes.generate()).build();

    private final UserRepository users = mock(UserRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final AssignmentService assignments = mock(AssignmentService.class);
    private final AuditService audit = mock(AuditService.class);
    private final MutableClock clock = new MutableClock();
    private final AccessControlService access = new AccessControlService(users, courses, mock(DocumentRepository.class),
            enrollments, audit);
    private final CourseService service = new CourseService(courses, enrollments, mock(TaskRepository.class), users,
            assignments, access, audit, new JoinAttemptLimiter(clock), mock(CoursePrivacyRemask.class));

    @BeforeEach
    void setUp() {
        when(users.findByEmail("student@uni.kz")).thenReturn(Optional.of(student));
        when(users.findByEmail("teacher@uni.kz")).thenReturn(Optional.of(teacher));
        when(courses.findWithTeacherById(345L)).thenReturn(Optional.of(course));
        // Looks the code up against the course's current one, like the unique column would.
        when(courses.findByInviteCode(anyString())).thenAnswer(invocation ->
                invocation.getArgument(0).equals(course.getInviteCode()) ? Optional.of(course) : Optional.empty());
        when(courses.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignments.handOut(345L)).thenReturn(3);
    }

    @Test
    void validCodeEnrollsAndHandsOutTheCourseTasks() {
        when(enrollments.enroll(345L, 1L)).thenReturn(1);

        assertThat(service.join(course.getInviteCode(), "student@uni.kz")).isEqualTo(345L);

        verify(enrollments).enroll(345L, 1L);
        verify(assignments).handOut(345L);
        verify(audit).record(eq("student@uni.kz"), eq(AuditService.STUDENT_ENROLLED), anyString());
    }

    @Test
    void joiningTwiceIsNotAnError() {
        when(enrollments.enroll(345L, 1L)).thenReturn(1).thenReturn(0);

        assertThat(service.join(course.getInviteCode(), "student@uni.kz")).isEqualTo(345L);
        assertThat(service.join("  " + course.getInviteCode() + " ", "student@uni.kz")).isEqualTo(345L);

        verify(assignments, times(1)).handOut(345L); // Tasks only for the real enrollment.
    }

    @Test
    void wrongCodeIs404WithoutRevealingAnythingAndIsAudited() {
        assertNotFound("definitelyWrongCode123");
        assertNotFound("345"); // A course id is not a code.
        assertNotFound("../../admin");

        verify(enrollments, never()).enroll(anyLong(), anyLong());
        verify(audit, times(3)).record(eq("student@uni.kz"), eq(AuditService.COURSE_JOIN_FAILED), anyString());
    }

    @Test
    void eleventhWrongAttemptWithinTenMinutesIs429AndTheBlockExpires() {
        for (int attempt = 1; attempt <= JoinAttemptLimiter.MAX_FAILURES; attempt++) {
            assertNotFound("wrongCode-" + attempt + "-xxxxxxxx");
        }

        assertThatThrownBy(() -> service.join("wrongCode-11-xxxxxxxx", "student@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(ex.getMessage()).startsWith("Слишком много попыток");
                    assertThat(ex.getRetryAfterSeconds()).isPositive();
                });
        // While blocked, even the right code is refused: guessing cannot continue.
        assertThatThrownBy(() -> service.join(course.getInviteCode(), "student@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        clock.advance(JoinAttemptLimiter.WINDOW.plusSeconds(1));
        when(enrollments.enroll(345L, 1L)).thenReturn(1);
        assertThat(service.join(course.getInviteCode(), "student@uni.kz")).isEqualTo(345L);
    }

    @Test
    void studentCannotEnrollByBareCourseId() {
        assertThatThrownBy(() -> service.enroll(345L, null, "student@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getMessage()).isEqualTo(CourseService.JOIN_BY_LINK_ONLY);
                });
        assertThatThrownBy(() -> service.enroll(345L, 1L, "student@uni.kz")) // Naming themselves does not help.
                .isInstanceOf(ApiException.class);
        verify(enrollments, never()).enroll(anyLong(), anyLong());
        verify(audit, times(2)).record(eq("student@uni.kz"), eq(AuditService.ACCESS_DENIED), startsWith("self-enroll"));
    }

    @Test
    void teacherStillEnrollsAStudentById() {
        when(users.findById(1L)).thenReturn(Optional.of(student));
        when(enrollments.enroll(345L, 1L)).thenReturn(1);

        service.enroll(345L, 1L, "teacher@uni.kz");

        verify(enrollments).enroll(345L, 1L);
        verify(assignments).handOut(345L);
    }

    @Test
    void teacherReadsAndRegeneratesTheCodeAndTheOldCodeStopsWorking() {
        String old = course.getInviteCode();
        assertThat(service.inviteCode(345L, "teacher@uni.kz")).isEqualTo(old);

        String fresh = service.regenerateInviteCode(345L, "teacher@uni.kz");

        assertThat(fresh).isNotEqualTo(old).hasSize(InviteCodes.LENGTH).matches(InviteCodes.FORMAT);
        assertThat(service.inviteCode(345L, "teacher@uni.kz")).isEqualTo(fresh);
        verify(audit).record("teacher@uni.kz", AuditService.COURSE_INVITE_REGENERATED, "Course 345");

        assertNotFound(old);
        when(enrollments.enroll(345L, 1L)).thenReturn(1);
        assertThat(service.join(fresh, "student@uni.kz")).isEqualTo(345L);
    }

    @Test
    void studentCannotReadOrRegenerateTheCode() {
        when(enrollments.existsByStudentIdAndCourseId(1L, 345L)).thenReturn(true); // Even an enrolled student.

        assertThatThrownBy(() -> service.inviteCode(345L, "student@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.regenerateInviteCode(345L, "student@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void teachersDoNotJoinByLink() {
        assertThatThrownBy(() -> service.join(course.getInviteCode(), "teacher@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getMessage()).isEqualTo(CourseService.ONLY_STUDENTS_JOIN);
                });
    }

    @Test
    void generatedCodesAreLongRandomAndUrlSafe() {
        String a = InviteCodes.generate();
        String b = InviteCodes.generate();
        assertThat(a).hasSize(22).matches("[A-Za-z0-9_-]+").isNotEqualTo(b);
        assertThat(new Course().getInviteCode()).isNull(); // Filled by @PrePersist / the startup migration.
    }

    private void assertNotFound(String code) {
        assertThatThrownBy(() -> service.join(code, "student@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getMessage()).isEqualTo("Курс не найден");
                });
    }

    /** A clock the test moves forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-12T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
