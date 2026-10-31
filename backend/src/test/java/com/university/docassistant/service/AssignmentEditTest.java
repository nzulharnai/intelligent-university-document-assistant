package com.university.docassistant.service;

import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.AssignmentDtos.UpdateAssignmentRequest;
import com.university.docassistant.dto.TaskExtractDto;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-08 step 2: the course teacher fixes AI mistakes in assignments. Edits reach the students' tasks without touching
 * their statuses or submissions; only the course's teacher and ADMIN may edit, close or reopen. Uses the real
 * AccessControlService so the 403s are the real decisions.
 */
class AssignmentEditTest {

    private static final LocalDateTime OLD_DEADLINE = LocalDateTime.of(2026, 11, 1, 18, 0);
    private static final LocalDateTime NEW_DEADLINE = LocalDateTime.of(2026, 11, 8, 23, 59);

    private final UserRepository users = mock(UserRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final AssignmentRepository assignments = mock(AssignmentRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final AssignmentEvents events = mock(AssignmentEvents.class);
    private final AccessControlService access = new AccessControlService(users, courses, mock(DocumentRepository.class),
            enrollments, audit);
    private final AssignmentService service = new AssignmentService(assignments, tasks, courses,
            mock(DocumentRepository.class), access, audit, events, false);

    private final User teacher = User.builder().id(2L).email("teacher@uni.kz").role(Role.ROLE_TEACHER).build();
    private final User otherTeacher = User.builder().id(3L).email("other@uni.kz").role(Role.ROLE_TEACHER).build();
    private final User student = User.builder().id(1L).email("student@uni.kz").role(Role.ROLE_STUDENT).build();
    private final Course course = Course.builder().id(10L).title("Algorithms").teacher(teacher).build();
    private final Document syllabus = Document.builder().id(50L).build();

    private Assignment lab;

    @BeforeEach
    void setUp() {
        for (User user : List.of(teacher, otherTeacher, student)) {
            when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        }
        when(courses.findWithTeacherById(10L)).thenReturn(Optional.of(course));
        when(enrollments.existsByStudentIdAndCourseId(1L, 10L)).thenReturn(true);
        lab = Assignment.builder().id(1L).title("Lab 1").requirements("AI text").deadline(OLD_DEADLINE)
                .status(AssignmentStatus.ACTIVE).course(course).sourceDocument(syllabus).build();
        when(assignments.findById(1L)).thenReturn(Optional.of(lab));
        when(assignments.findViewById(1L)).thenReturn(Optional.of(new AssignmentResponse(1L, "Lab 1", null, null,
                AssignmentStatus.ACTIVE, 10L, "Algorithms", null, null, null, null, null, null, null, false, true, null)));
    }

    private static UpdateAssignmentRequest edit(String title, LocalDateTime deadline) {
        return new UpdateAssignmentRequest(title, "Implement quicksort", deadline);
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(status));
    }

    @Nested
    class Edit {

        @Test
        void editReachesTheStudentsTasksWithoutTouchingStatusesOrSubmissions() {
            service.update(1L, edit("Lab 1: sorting", OLD_DEADLINE), "teacher@uni.kz");

            assertThat(lab.getTitle()).isEqualTo("Lab 1: sorting");
            assertThat(lab.getRequirements()).isEqualTo("Implement quicksort");
            verify(tasks).syncWithAssignments(10L); // Copies title, description, deadline only.
            verify(tasks, never()).updateStatus(anyLong(), any());
            verify(tasks, never()).deleteByAssignmentIdIn(anyCollection());
            verify(tasks, never()).fanOut(anyLong());
        }

        @Test
        void deadlineChangeOfAnActiveAssignmentIsAuditedWithOldAndNewValueAndReported() {
            service.update(1L, edit("Lab 1", NEW_DEADLINE), "teacher@uni.kz");

            verify(audit).record(eq("teacher@uni.kz"), eq(AuditService.ASSIGNMENT_DEADLINE_CHANGED),
                    contains("2026-11-01T18:00 -> 2026-11-08T23:59"));
            verify(events).deadlineChanged(lab, OLD_DEADLINE, NEW_DEADLINE);
        }

        @Test
        void sameDeadlineIsNotADeadlineChange() {
            service.update(1L, edit("Lab 1 renamed", OLD_DEADLINE), "teacher@uni.kz");

            verify(audit, never()).record(anyString(), eq(AuditService.ASSIGNMENT_DEADLINE_CHANGED), anyString());
            verify(events, never()).deadlineChanged(any(), any(), any());
        }

        @Test
        void editingADraftNeedsNoTaskSyncAndIsNotADeadlineChange() {
            lab.setStatus(AssignmentStatus.DRAFT);

            service.update(1L, edit("Lab 1", null), "teacher@uni.kz");

            assertThat(lab.getDeadline()).isNull(); // A missing deadline is allowed.
            verify(tasks, never()).syncWithAssignments(anyLong());
            verify(events, never()).deadlineChanged(any(), any(), any());
        }

        @Test
        void closedAssignmentCanBeEditedToo() {
            lab.setStatus(AssignmentStatus.CLOSED);

            service.update(1L, edit("Lab 1 (archive)", NEW_DEADLINE), "teacher@uni.kz");

            verify(tasks).syncWithAssignments(10L);
            // Deadline changes are reported only for ACTIVE assignments.
            verify(events, never()).deadlineChanged(any(), any(), any());
        }

        @Test
        void blankTitleIsRejectedAndNothingChanges() {
            assertThatThrownBy(() -> service.update(1L, edit("   ", NEW_DEADLINE), "teacher@uni.kz"))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.ASSIGNMENT_TITLE_REQUIRED));
            assertThat(lab.getTitle()).isEqualTo("Lab 1");
            assertThat(lab.getDeadline()).isEqualTo(OLD_DEADLINE);
        }

        @Test
        void requestValidationRequiresATitleOfAtMost255AndRequirementsOfAtMost2000Characters() {
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

            assertThat(validator.validate(new UpdateAssignmentRequest("Lab", null, null))).isEmpty();
            assertThat(validator.validate(new UpdateAssignmentRequest(" ", null, null))).hasSize(1);
            assertThat(validator.validate(new UpdateAssignmentRequest(null, null, null))).hasSize(1);
            assertThat(validator.validate(new UpdateAssignmentRequest("x".repeat(256), null, null))).hasSize(1);
            assertThat(validator.validate(new UpdateAssignmentRequest("Lab", "x".repeat(2001), null))).hasSize(1);
        }
    }

    @Nested
    class Access {

        @Test
        void teacherOfAnotherCourseGets403() {
            assertStatus(() -> service.update(1L, edit("Hijacked", NEW_DEADLINE), "other@uni.kz"), HttpStatus.FORBIDDEN);
            assertStatus(() -> service.close(1L, "other@uni.kz"), HttpStatus.FORBIDDEN);
            assertStatus(() -> service.reopen(1L, "other@uni.kz"), HttpStatus.FORBIDDEN);

            assertThat(lab.getTitle()).isEqualTo("Lab 1");
            verify(assignments, never()).saveAndFlush(any());
        }

        @Test
        void enrolledStudentGets403() {
            assertStatus(() -> service.update(1L, edit("Mine now", NEW_DEADLINE), "student@uni.kz"), HttpStatus.FORBIDDEN);
            assertStatus(() -> service.close(1L, "student@uni.kz"), HttpStatus.FORBIDDEN);
            assertStatus(() -> service.reopen(1L, "student@uni.kz"), HttpStatus.FORBIDDEN);

            assertThat(lab.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            verify(tasks, never()).syncWithAssignments(anyLong());
        }
    }

    @Nested
    class CloseAndReopen {

        @Test
        void reopenMakesItActiveAndHandsOutMissingTasks() {
            lab.setStatus(AssignmentStatus.CLOSED);
            when(tasks.fanOut(10L)).thenReturn(2); // Two students enrolled while it was closed.

            service.reopen(1L, "teacher@uni.kz");

            assertThat(lab.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            verify(tasks).fanOut(10L);
            verify(audit).record(eq("teacher@uni.kz"), eq(AuditService.ASSIGNMENT_REOPENED), contains("2 new student task(s)"));
        }

        @Test
        void reopeningAnActiveAssignmentIsANoOpAndADraftCannotBeReopened() {
            service.reopen(1L, "teacher@uni.kz");
            verify(tasks, never()).fanOut(anyLong());

            lab.setStatus(AssignmentStatus.DRAFT);
            assertThatThrownBy(() -> service.reopen(1L, "teacher@uni.kz")).isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.ASSIGNMENT_IS_DRAFT));
        }
    }

    /** The teacher's edit survives a re-analysis: the AI item is still recognised, nothing is duplicated or removed. */
    @Nested
    class TakenOverByTheTeacher {

        @Test
        @SuppressWarnings("unchecked")
        void reAnalysisKeepsTheTeachersEditAndCreatesNoDuplicate() {
            service.update(1L, edit("Lab 1: sorting", NEW_DEADLINE), "teacher@uni.kz");
            when(assignments.findBySourceDocumentId(50L)).thenReturn(List.of(lab));

            // The AI finds the same item again, in its original wording.
            service.replaceFromDocument(50L, 10L, List.of(new TaskExtractDto("Lab 1", "AI text again", OLD_DEADLINE)));

            ArgumentCaptor<List<Assignment>> saved = ArgumentCaptor.forClass(List.class);
            verify(assignments).saveAll(saved.capture());
            assertThat(saved.getValue()).containsExactly(lab); // Matched, not a new draft.
            assertThat(lab.getTitle()).isEqualTo("Lab 1: sorting");
            assertThat(lab.getRequirements()).isEqualTo("Implement quicksort");
            assertThat(lab.getDeadline()).isEqualTo(NEW_DEADLINE);
        }

        @Test
        void reAnalysisThatNoLongerFindsItLeavesItAlone() {
            service.update(1L, edit("Lab 1: sorting", NEW_DEADLINE), "teacher@uni.kz");
            when(assignments.findBySourceDocumentId(50L)).thenReturn(List.of(lab));

            service.replaceFromDocument(50L, 10L, List.of());

            assertThat(lab.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            verify(tasks, never()).findAssignmentIdsWithActivity(anyCollection());
            verify(assignments, never()).deleteAllByIdInBatch(any());
        }
    }
}
