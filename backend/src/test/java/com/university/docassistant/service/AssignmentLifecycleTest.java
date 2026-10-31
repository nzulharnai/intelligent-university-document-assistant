package com.university.docassistant.service;

import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.TaskExtractDto;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.AuditLogRepository;
import com.university.docassistant.repository.BatchImportRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskRepository.SubmitRules;
import com.university.docassistant.repository.TaskRepository.TaskAccess;
import com.university.docassistant.repository.TaskSubmissionRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.JoinAttemptLimiter;
import com.university.docassistant.security.Permission;
import com.university.docassistant.service.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Student work (submissions, changed task statuses) is never deleted: assignments with such tasks are CLOSED instead,
 * on every path that used to delete them; assignments without activity are still deleted as before.
 */
class AssignmentLifecycleTest {

    private static final String TEACHER = "teacher@uni.kz";

    private final AssignmentRepository assignments = mock(AssignmentRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final AuditService audit = mock(AuditService.class);
    private final AssignmentService service = new AssignmentService(assignments, tasks, mock(CourseRepository.class),
            mock(DocumentRepository.class), access, audit, mock(AssignmentEvents.class), false);

    private final User teacher = User.builder().id(2L).email(TEACHER).role(Role.ROLE_TEACHER).build();
    private final Course course = Course.builder().id(10L).title("Algorithms").build();
    private final Document document = Document.builder().id(50L).build();

    @BeforeEach
    void setUp() {
        when(access.currentUser(TEACHER)).thenReturn(teacher);
        when(access.authorizeCourse(teacher, 10L, Permission.MANAGE_ASSIGNMENTS)).thenReturn(course);
    }

    private Assignment assignment(long id, String title) {
        return Assignment.builder().id(id).title(title).status(AssignmentStatus.ACTIVE).course(course)
                .sourceDocument(document).deadline(LocalDateTime.of(2026, 11, 1, 18, 0)).build();
    }

    private void activityOn(Long... assignmentIds) {
        when(tasks.findAssignmentIdsWithActivity(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> asked = invocation.getArgument(0);
            return List.of(assignmentIds).stream().filter(asked::contains).toList();
        });
    }

    private static void assertConflict(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }

    /** a) A re-analysis that no longer finds an assignment (e.g. reworded title). */
    @Nested
    class ReplaceFromDocument {

        private final TaskExtractDto reworded = new TaskExtractDto("Lab 1 (revised wording)", "Requirements",
                LocalDateTime.of(2026, 11, 1, 18, 0));

        @Test
        void vanishedAssignmentWithActivityIsClosedNotDeleted() {
            Assignment old = assignment(1L, "Lab 1");
            when(assignments.findBySourceDocumentId(50L)).thenReturn(List.of(old));
            activityOn(1L);

            service.replaceFromDocument(50L, 10L, List.of(reworded));

            assertThat(old.getStatus()).isEqualTo(AssignmentStatus.CLOSED);
            verify(tasks, never()).deleteByAssignmentIdIn(argThat(ids -> ids.contains(1L)));
            verify(assignments, never()).deleteAllByIdInBatch(argThat(ids -> iterableContains(ids, 1L)));
            // The analysis runs in the background: no signed-in user.
            verify(audit).record(isNull(), eq(AuditService.ASSIGNMENT_CLOSED), contains("Assignment 1"));
        }

        @Test
        void vanishedAssignmentWithoutActivityIsDeletedAsBefore() {
            Assignment old = assignment(1L, "Lab 1");
            when(assignments.findBySourceDocumentId(50L)).thenReturn(List.of(old));
            activityOn();

            service.replaceFromDocument(50L, 10L, List.of(reworded));

            verify(tasks).deleteByAssignmentIdIn(List.of(1L));
            verify(assignments).deleteAllByIdInBatch(List.of(1L));
            verify(audit, never()).record(any(), eq(AuditService.ASSIGNMENT_CLOSED), anyString());
        }

        @Test
        void alreadyClosedAssignmentIsNotClosedAgainOnTheNextAnalysis() {
            Assignment old = assignment(1L, "Lab 1");
            old.setStatus(AssignmentStatus.CLOSED);
            when(assignments.findBySourceDocumentId(50L)).thenReturn(List.of(old));
            activityOn(1L);

            service.replaceFromDocument(50L, 10L, List.of(reworded));

            verify(audit, never()).record(any(), eq(AuditService.ASSIGNMENT_CLOSED), anyString());
            verify(tasks, never()).deleteByAssignmentIdIn(argThat(ids -> ids.contains(1L)));
        }

        @Test
        void partialAnalysisKeepsWhatItDidNotFindAndStillAddsWhatItFound() {
            Assignment published = assignment(1L, "Lab 1");
            Assignment draft = assignment(2L, "Lab 2");
            draft.setStatus(AssignmentStatus.DRAFT);
            when(assignments.findBySourceDocumentId(50L)).thenReturn(List.of(published, draft));
            activityOn();

            // Lab 1 and Lab 2 may sit in the part of the text the AI did not answer for.
            int found = service.replaceFromDocument(50L, 10L, List.of(reworded), false);

            assertThat(found).isEqualTo(1);
            assertThat(published.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            assertThat(draft.getStatus()).isEqualTo(AssignmentStatus.DRAFT);
            verify(tasks, never()).deleteByAssignmentIdIn(anyList());
            verify(assignments, never()).deleteAllByIdInBatch(any());
            verify(audit, never()).record(any(), eq(AuditService.ASSIGNMENT_CLOSED), anyString());
            verify(assignments).saveAll(argThat((List<Assignment> saved) -> saved.size() == 1
                    && saved.get(0).getTitle().equals(reworded.title()) && saved.get(0).getId() == null));
        }
    }

    /** b) The document an assignment came from is deleted (also a teacher's documents when the teacher is deleted). */
    @Nested
    class ReleaseDocuments {

        @Test
        void assignmentWithActivityIsClosedAndUnlinkedFromTheDocument() {
            Assignment worked = assignment(1L, "Lab 1");
            Assignment untouched = assignment(2L, "Lab 2");
            when(assignments.findBySourceDocumentIdIn(List.of(50L))).thenReturn(List.of(worked, untouched));
            activityOn(1L);

            service.releaseDocuments(List.of(50L), TEACHER);

            assertThat(worked.getStatus()).isEqualTo(AssignmentStatus.CLOSED);
            assertThat(worked.getSourceDocument()).isNull();
            verify(tasks).detachDocumentFromAssignmentTasks(List.of(1L));
            verify(tasks).detachDocumentFromTasksWithActivity(List.of(50L));
            // Only the untouched one goes.
            verify(tasks).deleteByAssignmentIdIn(List.of(2L));
            verify(assignments).deleteAllByIdInBatch(List.of(2L));
            verify(audit).record(eq(TEACHER), eq(AuditService.ASSIGNMENT_CLOSED), contains("Assignment 1"));
        }

        @Test
        void withoutActivityEverythingIsDeletedAsBefore() {
            Assignment untouched = assignment(2L, "Lab 2");
            when(assignments.findBySourceDocumentIdIn(List.of(50L))).thenReturn(List.of(untouched));
            activityOn();

            service.releaseDocuments(List.of(50L), TEACHER);

            verify(tasks).deleteByAssignmentIdIn(List.of(2L));
            verify(tasks, never()).detachDocumentFromAssignmentTasks(anyCollection());
            verify(audit, never()).record(any(), eq(AuditService.ASSIGNMENT_CLOSED), anyString());
        }

        @Test
        void documentDeletionReleasesTheAssignmentsBeforeDeletingAnything() {
            DocumentRepository documents = mock(DocumentRepository.class);
            AssignmentService assignmentService = mock(AssignmentService.class);
            DocumentService documentService = new DocumentService(documents, tasks, assignments, access,
                    mock(StorageService.class), mock(DocumentParserService.class), mock(DocumentAnalysisService.class),
                    mock(AiClientService.class), mock(DocumentChunkService.class), audit,
                    mock(DocumentClassificationService.class), mock(DocumentProcessingService.class),
                    mock(BatchImportRepository.class), assignmentService,
                    new DocumentPrivacyService(new PrivacyProperties(null, null)));
            Document doc = Document.builder().id(50L).originalFileName("syllabus.pdf").storedFileName("x").build();
            when(access.authorizeDocument(teacher, 50L, Permission.DELETE_DOCUMENT)).thenReturn(doc);

            withSynchronization(() -> documentService.delete(50L, TEACHER));

            InOrder order = inOrder(assignmentService, tasks, assignments, documents);
            order.verify(assignmentService).releaseDocuments(List.of(50L), TEACHER);
            order.verify(tasks).deleteByAssignmentSourceDocumentId(50L);
            order.verify(assignments).deleteBySourceDocumentId(50L);
            order.verify(tasks).deleteByDocumentId(50L);
            order.verify(documents).delete(doc);
        }

        @Test
        void deletingATeacherReleasesTheirDocumentsFirst() {
            UserRepository users = mock(UserRepository.class);
            DocumentRepository documents = mock(DocumentRepository.class);
            AssignmentService assignmentService = mock(AssignmentService.class);
            AdminService admin = new AdminService(users, documents, mock(CourseRepository.class), tasks,
                    mock(TaskSubmissionRepository.class), assignments, mock(EnrollmentRepository.class),
                    mock(AuditLogRepository.class), audit, mock(StorageService.class), assignmentService,
                    mock(NotificationService.class));
            when(users.findById(2L)).thenReturn(Optional.of(teacher));
            when(documents.findIdsByOwnerId(2L)).thenReturn(List.of(50L, 51L));

            withSynchronization(() -> admin.deleteUser(2L, "dean@uni.kz"));

            InOrder order = inOrder(assignmentService, tasks, assignments);
            order.verify(assignmentService).releaseDocuments(List.of(50L, 51L), "dean@uni.kz");
            order.verify(tasks).deleteByAssignmentSourceDocumentOwnerId(2L);
            order.verify(assignments).deleteBySourceDocumentOwnerId(2L);
        }
    }

    /** c) Deleting an assignment by hand; closing it explicitly. */
    @Nested
    class DeleteAndClose {

        @BeforeEach
        void courseOfAssignment() {
            when(assignments.findCourseIdById(1L)).thenReturn(Optional.of(10L));
            when(assignments.findViewById(1L)).thenReturn(Optional.of(new AssignmentResponse(1L, "Lab 1", null, null,
                    AssignmentStatus.CLOSED, 10L, "Algorithms", null, null, null, null, null, null, null, false, true, null)));
        }

        @Test
        void deletingAnAssignmentWithActivityIs409AndDeletesNothing() {
            activityOn(1L);

            assertConflict(() -> service.delete(1L, TEACHER), ErrorCodes.ASSIGNMENT_HAS_ACTIVITY);

            verify(tasks, never()).deleteByAssignmentIdIn(anyCollection());
            verify(assignments, never()).deleteById(anyLong());
        }

        @Test
        void conflictMessageSuggestsClosingInRussian() {
            activityOn(1L);

            assertThatThrownBy(() -> service.delete(1L, TEACHER)).hasMessageContaining("Закройте задание");
        }

        @Test
        void assignmentWithoutActivityIsDeletedAsBefore() {
            activityOn();

            service.delete(1L, TEACHER);

            verify(tasks).deleteByAssignmentIdIn(List.of(1L));
            verify(assignments).deleteById(1L);
            verify(audit).record(eq(TEACHER), eq(AuditService.ASSIGNMENT_DELETED), anyString());
        }

        @Test
        void closeSetsClosedKeepsTasksAndIsAudited() {
            Assignment lab = assignment(1L, "Lab 1");
            when(assignments.findById(1L)).thenReturn(Optional.of(lab));

            service.close(1L, TEACHER);

            assertThat(lab.getStatus()).isEqualTo(AssignmentStatus.CLOSED);
            verify(tasks, never()).deleteByAssignmentIdIn(anyCollection());
            verify(audit).record(eq(TEACHER), eq(AuditService.ASSIGNMENT_CLOSED), contains("closed by hand"));
        }

        @Test
        void closingTwiceIsAuditedOnce() {
            Assignment lab = assignment(1L, "Lab 1");
            when(assignments.findById(1L)).thenReturn(Optional.of(lab));

            service.close(1L, TEACHER);
            service.close(1L, TEACHER);

            verify(audit, times(1)).record(any(), eq(AuditService.ASSIGNMENT_CLOSED), anyString());
        }
    }

    /** d) Unenrolling keeps the student's tasks; enrolling again hands out only what is missing. */
    @Nested
    class Unenroll {

        private final User student = User.builder().id(1L).email("student@uni.kz").role(Role.ROLE_STUDENT).build();
        private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
        private final CourseRepository courses = mock(CourseRepository.class);
        private final AssignmentService assignmentService = mock(AssignmentService.class);
        private final CourseService courseService = new CourseService(courses, enrollments, tasks,
                mock(UserRepository.class), assignmentService, access, audit, new JoinAttemptLimiter(), mock(CoursePrivacyRemask.class));

        @Test
        void unenrollKeepsTasksAndSubmissions() {
            when(access.authorizeCourse(teacher, 10L, Permission.MANAGE_COURSE)).thenReturn(course);
            when(enrollments.unenroll(10L, 1L)).thenReturn(1);

            courseService.unenroll(10L, 1L, TEACHER);

            verify(tasks, never()).deleteByCourseIdAndUserId(anyLong(), anyLong());
            verify(tasks, never()).deleteByUserId(anyLong());
            verify(audit).record(eq(TEACHER), eq(AuditService.STUDENT_UNENROLLED), anyString());
        }

        @Test
        void studentWhoLeftCannotWorkOnTheKeptTask() {
            UserRepository users = mock(UserRepository.class);
            TaskService taskService = new TaskService(tasks, new AccessControlService(users, courses,
                    mock(DocumentRepository.class), enrollments, mock(AuditService.class)), Clock.systemUTC());
            when(users.findByEmail("student@uni.kz")).thenReturn(Optional.of(student));
            when(tasks.findAccessById(7L)).thenReturn(Optional.of(new TaskAccess(7L, 1L, 10L, 3L)));
            when(enrollments.existsByStudentIdAndCourseId(1L, 10L)).thenReturn(false);

            assertThatThrownBy(() -> taskService.updateStatus(7L, TaskStatus.IN_PROGRESS, "student@uni.kz"))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.COURSE_NOT_ENROLLED));
            verify(tasks, never()).updateStatus(anyLong(), any());
        }
    }

    /** Closed assignments take no more submissions. */
    @Test
    void submissionToAClosedAssignmentIs409() {
        User student = User.builder().id(1L).email("s@uni.kz").role(Role.ROLE_STUDENT).build();
        CourseRepository courses = mock(CourseRepository.class);
        StorageService storage = mock(StorageService.class);
        TaskSubmissionRepository submissions = mock(TaskSubmissionRepository.class);
        SubmissionService submissionService = new SubmissionService(submissions, tasks, assignments, courses, access,
                storage, audit, new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.systemUTC());
        when(access.currentUser("s@uni.kz")).thenReturn(student);
        when(tasks.findAccessById(7L)).thenReturn(Optional.of(new TaskAccess(7L, 1L, 10L, 3L)));
        when(courses.findById(10L)).thenReturn(Optional.of(course));
        when(access.isMember(student, course)).thenReturn(true);
        when(tasks.findSubmitRulesById(7L)).thenReturn(Optional.of(
                new SubmitRules(TaskStatus.PENDING, null, true, AssignmentStatus.CLOSED)));

        assertConflict(() -> submissionService.submit(7L,
                List.of(new MockMultipartFile("files", "solution.pdf", "application/pdf", new byte[]{1})), "s@uni.kz"),
                ErrorCodes.ASSIGNMENT_CLOSED);
        verify(storage, never()).store(any(byte[].class));
        verify(submissions, never()).saveAll(anyList());
    }

    private static boolean iterableContains(Iterable<Long> ids, Long id) {
        for (Long value : ids) {
            if (value.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** delete() registers an after-commit hook for the files; outside Spring there is no transaction to hold it. */
    private static void withSynchronization(Runnable call) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            call.run();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @AfterEach
    void noLeftoverSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
