package com.university.docassistant.service;

import com.university.docassistant.config.DocumentPrivacyBackfill;
import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.AuthDtos.UpdateProfileRequest;
import com.university.docassistant.dto.AuthDtos.UserResponse;
import com.university.docassistant.dto.TaskResponse;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentRepository.PrivacySource;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.JoinAttemptLimiter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review fix: the teacher's phone joins the masking exemptions; when the exemptions of a course change (another
 * teacher, a new phone) its documents are masked again in the background. Also: the fingerprint ignores the order of
 * the public domains, and students' task lists show masked document names.
 */
class PrivacyExemptionChangeTest {

    private final AccessControlService access = mock(AccessControlService.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final CoursePrivacyRemask remask = mock(CoursePrivacyRemask.class);
    private final AuditService audit = mock(AuditService.class);

    private final User teacher = User.builder().id(2L).email("teacher@uni.kz").fullName("Петров П.")
            .role(Role.ROLE_TEACHER).build();

    @Nested
    class Profile {

        private final ProfileService service = new ProfileService(access, courses, remask, audit);

        @Test
        void savesNameAndPhoneAsDigitsAndRemasksTheTeachersCourses() {
            when(access.currentUser("teacher@uni.kz")).thenReturn(teacher);
            when(courses.findIdsByTeacherId(2L)).thenReturn(List.of(10L, 11L));

            UserResponse response = service.update("teacher@uni.kz",
                    new UpdateProfileRequest("  Петров Пётр  ", "+7 (701) 123-45-67"));

            assertThat(teacher.getFullName()).isEqualTo("Петров Пётр");
            assertThat(teacher.getPhone()).isEqualTo("77011234567");
            assertThat(response.phone()).isEqualTo("77011234567");
            verify(remask).schedule(List.of(10L, 11L));
            ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
            verify(audit).record(eq("teacher@uni.kz"), eq(AuditService.PROFILE_UPDATED), details.capture());
            assertThat(details.getValue()).contains("name", "phone").doesNotContain("7011234567");
        }

        @Test
        void nameOnlyOrTheSamePhoneDoesNotRemask() {
            teacher.setPhone("77011234567");
            when(access.currentUser("teacher@uni.kz")).thenReturn(teacher);

            service.update("teacher@uni.kz", new UpdateProfileRequest("Петров Пётр", "+7 (701) 123 45 67"));
            service.update("teacher@uni.kz", new UpdateProfileRequest("Петров П.", "77011234567"));

            verify(remask, never()).schedule(anyCollection());
        }

        @Test
        void emptyPhoneRemovesItAndInvalidPhoneIsRefused() {
            teacher.setPhone("77011234567");
            when(access.currentUser("teacher@uni.kz")).thenReturn(teacher);

            service.update("teacher@uni.kz", new UpdateProfileRequest("Петров П.", " "));
            assertThat(teacher.getPhone()).isNull();

            assertThatThrownBy(() -> service.update("teacher@uni.kz", new UpdateProfileRequest("Петров П.", "12-34")))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PHONE_INVALID);
                    });
            assertThat(teacher.getPhone()).isNull();
        }
    }

    @Nested
    class TeacherChange {

        private final UserRepository users = mock(UserRepository.class);
        private final CourseService service = new CourseService(courses, mock(EnrollmentRepository.class),
                mock(TaskRepository.class), users, mock(AssignmentService.class), access, audit,
                new JoinAttemptLimiter(), remask);
        private final User admin = User.builder().id(9L).email("dean@uni.kz").role(Role.ROLE_ADMIN).build();
        private final User newTeacher = User.builder().id(3L).email("new@uni.kz").role(Role.ROLE_TEACHER).build();

        @Test
        void anotherTeacherRemasksTheCourseTheSameTeacherDoesNot() {
            Course course = Course.builder().id(10L).title("Algorithms").teacher(teacher).build();
            when(access.currentUser("dean@uni.kz")).thenReturn(admin);
            when(courses.findWithTeacherById(10L)).thenReturn(Optional.of(course));
            when(users.findById(3L)).thenReturn(Optional.of(newTeacher));
            when(users.findById(2L)).thenReturn(Optional.of(teacher));

            service.assignTeacher(10L, 3L, "dean@uni.kz");
            verify(remask).schedule(List.of(10L));

            org.mockito.Mockito.clearInvocations(remask);
            service.assignTeacher(10L, 3L, "dean@uni.kz");
            verify(remask, never()).schedule(anyCollection());
        }
    }

    @Nested
    class Remask {

        private final DocumentRepository documents = mock(DocumentRepository.class);
        private final DocumentPrivacyBackfill backfill = mock(DocumentPrivacyBackfill.class);

        @Test
        void marksTheCoursesDocumentsOutdatedAndRemasksThemInTheBackground() {
            when(documents.markPrivacyOutdatedInCourses(List.of(10L))).thenReturn(3);

            new CoursePrivacyRemask(documents, backfill, new SyncTaskExecutor()).schedule(List.of(10L));

            verify(documents).markPrivacyOutdatedInCourses(List.of(10L));
            verify(backfill).backfillCourses(List.of(10L));
        }

        @Test
        void nothingToMaskOrAFullQueueIsNoError() {
            TaskExecutor full = mock(TaskExecutor.class);
            doThrow(new TaskRejectedException("full")).when(full).execute(any());
            when(documents.markPrivacyOutdatedInCourses(List.of(11L))).thenReturn(1);

            new CoursePrivacyRemask(documents, backfill, new SyncTaskExecutor()).schedule(List.of());
            new CoursePrivacyRemask(documents, backfill, new SyncTaskExecutor()).schedule(List.of(12L)); // 0 marked
            assertThatNoException().isThrownBy(() -> new CoursePrivacyRemask(documents, backfill, full).schedule(List.of(11L)));

            verify(backfill, never()).backfillCourses(anyCollection());
        }

        @Test
        void scopedBackfillMasksOnlyThoseCoursesAndKeepsTheTeachersPhone() {
            DocumentPrivacyService privacy = new DocumentPrivacyService(new PrivacyProperties(true, List.of()));
            DocumentChunkService chunks = mock(DocumentChunkService.class);
            String text = "Преподаватель: teacher@uni.kz, тел. +7 702 555 12 34. Староста: +7 701 123 45 67.";
            when(documents.findPrivacyBatchInCourses(anyString(), eq(List.of(10L)), eq(0L), any(Pageable.class)))
                    .thenReturn(List.of(new PrivacySource(50L, text, "teacher@uni.kz", "77025551234", null)));
            when(documents.findPrivacyBatchInCourses(anyString(), eq(List.of(10L)), eq(50L), any(Pageable.class)))
                    .thenReturn(List.of());

            int done = new DocumentPrivacyBackfill(documents, privacy, chunks, mock(PlatformTransactionManager.class))
                    .backfillCourses(List.of(10L));

            assertThat(done).isEqualTo(1);
            ArgumentCaptor<String> masked = ArgumentCaptor.forClass(String.class);
            verify(documents).updatePrivacy(eq(50L), masked.capture(), anyString(), eq(1), eq("PHONE"),
                    eq(privacy.fingerprint()));
            assertThat(masked.getValue()).contains("teacher@uni.kz", "+7 702 555 12 34", "Староста: [телефон скрыт]");
            verify(chunks).index(50L, masked.getValue());
            verify(documents, never()).findPrivacyBatch(anyString(), anyLong(), any(Pageable.class));
        }
    }

    @Test
    void fingerprintIgnoresTheOrderOfThePublicDomains() {
        assertThat(new DocumentPrivacyService(new PrivacyProperties(true, List.of("uni.kz", "sdu.edu.kz"))).fingerprint())
                .isEqualTo(new DocumentPrivacyService(new PrivacyProperties(true, List.of("sdu.edu.kz", "uni.kz", "uni.kz")))
                        .fingerprint());
    }

    @Test
    void studentsTasksShowTheDocumentNameMasked() {
        TaskRepository tasks = mock(TaskRepository.class);
        User student = User.builder().id(1L).email("student@uni.kz").role(Role.ROLE_STUDENT).build();
        TaskResponse task = new TaskResponse(5L, "Lab 1", null, null, TaskStatus.PENDING, 50L,
                "Иванов_990101300013.pdf", 10L, "Algorithms", "INF345", null, null, 0L,
                null, false, null, null, null, null, null);
        when(tasks.findMyViews(eq(1L), eq("ALL"), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(task)));

        AccessControlService access = mock(AccessControlService.class);
        when(access.currentUser("student@uni.kz")).thenReturn(student);
        var page = new TaskService(tasks, access, Clock.systemUTC())
                .listMine("student@uni.kz", null, null, PageRequest.of(0, 10));

        assertThat(page.content()).singleElement().satisfies(row -> {
            assertThat(row.documentName()).isEqualTo("Иванов_[ИИН скрыт].pdf");
            assertThat(row.title()).isEqualTo("Lab 1");
        });
        verify(tasks, never()).findUpcomingViews(anyLong(), any(), any());
    }
}
