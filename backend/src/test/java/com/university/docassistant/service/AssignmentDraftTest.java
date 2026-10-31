package com.university.docassistant.service;

import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.AssignmentDtos.PublishResponse;
import com.university.docassistant.dto.DocumentStatusResponse;
import com.university.docassistant.dto.TaskExtractDto;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.BatchImportRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import com.university.docassistant.service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-08: what the AI finds is a DRAFT that the course teacher reviews; students never see drafts and get no tasks
 * for them until they are published. Query-level guarantees (fanOut, lists, task views) run against PostgreSQL in
 * {@link AssignmentLifecyclePostgresTest}.
 */
class AssignmentDraftTest {

    private static final String TEACHER = "teacher@uni.kz";

    private final AssignmentRepository assignments = mock(AssignmentRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final AuditService audit = mock(AuditService.class);
    private final AssignmentService service = service(false);

    private final User teacher = User.builder().id(2L).email(TEACHER).role(Role.ROLE_TEACHER).build();
    private final Course course = Course.builder().id(10L).title("Algorithms").teacher(teacher).build();
    private final Course otherCourse = Course.builder().id(11L).title("Databases").build();
    private final Document document = Document.builder().id(50L).build();

    private AssignmentService service(boolean autoPublish) {
        return new AssignmentService(assignments, tasks, mock(CourseRepository.class), mock(DocumentRepository.class),
                access, audit, mock(AssignmentEvents.class), autoPublish);
    }

    @BeforeEach
    void setUp() {
        when(access.currentUser(TEACHER)).thenReturn(teacher);
        when(access.authorizeCourse(teacher, 10L, Permission.MANAGE_ASSIGNMENTS)).thenReturn(course);
        when(assignments.findViewById(anyLong())).thenReturn(Optional.of(new AssignmentResponse(1L, "Lab 1", null, null,
                AssignmentStatus.ACTIVE, 10L, "Algorithms", null, null, null, null, null, null, null, false, true, null)));
    }

    private Assignment assignment(long id, String title, AssignmentStatus status, Course of) {
        return Assignment.builder().id(id).title(title).status(status).course(of).sourceDocument(document)
                .deadline(LocalDateTime.of(2026, 11, 1, 18, 0)).build();
    }

    private static void assertStatus(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }

    @SuppressWarnings("unchecked")
    private List<Assignment> savedByReplace() {
        ArgumentCaptor<List<Assignment>> saved = ArgumentCaptor.forClass(List.class);
        verify(assignments).saveAll(saved.capture());
        return saved.getValue();
    }

    @Nested
    class Analysis {

        private final List<TaskExtractDto> found = List.of(
                new TaskExtractDto("Lab 1", "Sorting", LocalDateTime.of(2026, 11, 1, 18, 0)),
                new TaskExtractDto("Exam", null, null));

        @Test
        void analysisCreatesDrafts() {
            service.replaceFromDocument(50L, 10L, found);

            assertThat(savedByReplace()).extracting(Assignment::getStatus)
                    .containsOnly(AssignmentStatus.DRAFT).hasSize(2);
            // fanOut runs as before; it hands out ACTIVE assignments only, so the drafts give no tasks.
            verify(tasks).fanOut(10L);
        }

        @Test
        void autoPublishKeepsTheOldBehavior() {
            service(true).replaceFromDocument(50L, 10L, found);

            assertThat(savedByReplace()).extracting(Assignment::getStatus).containsOnly(AssignmentStatus.ACTIVE);
        }

        @Test
        void reAnalysisReplacesDraftsButNeverDeletesWorkedOnPublishedAssignments() {
            Assignment matchedDraft = assignment(1L, "Lab 1", AssignmentStatus.DRAFT, course);
            Assignment vanishedDraft = assignment(2L, "Old wording", AssignmentStatus.DRAFT, course);
            Assignment workedOn = assignment(3L, "Lab 0", AssignmentStatus.ACTIVE, course);
            when(assignments.findBySourceDocumentId(50L)).thenReturn(List.of(matchedDraft, vanishedDraft, workedOn));
            when(tasks.findAssignmentIdsWithActivity(anyCollection())).thenReturn(List.of(3L));

            service.replaceFromDocument(50L, 10L, found);

            assertThat(matchedDraft.getStatus()).as("still a draft").isEqualTo(AssignmentStatus.DRAFT);
            assertThat(matchedDraft.getRequirements()).isEqualTo("Sorting");
            verify(assignments).deleteAllByIdInBatch(List.of(2L));
            assertThat(workedOn.getStatus()).isEqualTo(AssignmentStatus.CLOSED);
            verify(tasks, never()).deleteByAssignmentIdIn(List.of(3L));
            verify(audit).record(isNull(), eq(AuditService.ASSIGNMENT_CLOSED), contains("Assignment 3"));
        }
    }

    @Nested
    class Publish {

        @Test
        void publishMakesTheDraftActiveAndHandsItOut() {
            Assignment draft = assignment(1L, "Lab 1", AssignmentStatus.DRAFT, course);
            when(assignments.findById(1L)).thenReturn(Optional.of(draft));
            when(tasks.fanOut(10L)).thenReturn(25);

            service.publish(1L, TEACHER);

            assertThat(draft.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            verify(tasks).fanOut(10L);
            verify(audit).record(eq(TEACHER), eq(AuditService.ASSIGNMENT_PUBLISHED), contains("1 assignment(s)"));
        }

        @Test
        void missingDeadlineIsAllowedButMissingTitleIsNot() {
            Assignment noDeadline = assignment(1L, "Essay", AssignmentStatus.DRAFT, course);
            noDeadline.setDeadline(null);
            Assignment noTitle = assignment(2L, "  ", AssignmentStatus.DRAFT, course);
            when(assignments.findById(1L)).thenReturn(Optional.of(noDeadline));
            when(assignments.findById(2L)).thenReturn(Optional.of(noTitle));

            service.publish(1L, TEACHER);
            assertStatus(() -> service.publish(2L, TEACHER), HttpStatus.BAD_REQUEST, ErrorCodes.ASSIGNMENT_TITLE_REQUIRED);

            assertThat(noDeadline.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            assertThat(noTitle.getStatus()).isEqualTo(AssignmentStatus.DRAFT);
        }

        @Test
        void publishingSomethingPublishedIs400() {
            when(assignments.findById(1L)).thenReturn(Optional.of(assignment(1L, "Lab 1", AssignmentStatus.ACTIVE, course)));

            assertStatus(() -> service.publish(1L, TEACHER), HttpStatus.BAD_REQUEST, ErrorCodes.ASSIGNMENT_NOT_DRAFT);
            verify(tasks, never()).fanOut(anyLong());
        }

        @Test
        void publishAllPublishesEveryDraftAndHandsOutOnce() {
            Assignment first = assignment(1L, "Lab 1", AssignmentStatus.DRAFT, course);
            Assignment second = assignment(2L, "Lab 2", AssignmentStatus.DRAFT, course);
            when(assignments.findAllById(List.of(1L, 2L))).thenReturn(List.of(first, second));
            when(tasks.fanOut(10L)).thenReturn(50);

            PublishResponse response = service.publishAll(10L, List.of(1L, 2L, 2L), TEACHER);

            assertThat(response).isEqualTo(new PublishResponse(2, 50));
            assertThat(List.of(first, second)).extracting(Assignment::getStatus).containsOnly(AssignmentStatus.ACTIVE);
            verify(audit).record(eq(TEACHER), eq(AuditService.ASSIGNMENT_PUBLISHED), contains("2 assignment(s)"));
        }

        @Test
        void publishAllRejectsIdsOfAnotherCourseAndChangesNothing() {
            Assignment ours = assignment(1L, "Lab 1", AssignmentStatus.DRAFT, course);
            Assignment theirs = assignment(7L, "Their lab", AssignmentStatus.DRAFT, otherCourse);
            when(assignments.findAllById(List.of(1L, 7L))).thenReturn(List.of(ours, theirs));

            assertStatus(() -> service.publishAll(10L, List.of(1L, 7L), TEACHER),
                    HttpStatus.BAD_REQUEST, ErrorCodes.ASSIGNMENT_NOT_DRAFT);

            assertThat(ours.getStatus()).isEqualTo(AssignmentStatus.DRAFT);
            assertThat(theirs.getStatus()).isEqualTo(AssignmentStatus.DRAFT);
            verify(assignments, never()).saveAllAndFlush(anyIterable());
            verify(tasks, never()).fanOut(anyLong());
        }

        @Test
        void publishAllIsAllOrNothing() {
            Assignment draft = assignment(1L, "Lab 1", AssignmentStatus.DRAFT, course);
            Assignment published = assignment(2L, "Lab 2", AssignmentStatus.ACTIVE, course);
            when(assignments.findAllById(List.of(1L, 2L))).thenReturn(List.of(draft, published));
            when(assignments.findAllById(List.of(1L, 99L))).thenReturn(List.of(draft)); // 99 does not exist.

            assertStatus(() -> service.publishAll(10L, List.of(1L, 2L), TEACHER),
                    HttpStatus.BAD_REQUEST, ErrorCodes.ASSIGNMENT_NOT_DRAFT);
            assertStatus(() -> service.publishAll(10L, List.of(1L, 99L), TEACHER),
                    HttpStatus.BAD_REQUEST, ErrorCodes.ASSIGNMENT_NOT_DRAFT);

            assertThat(draft.getStatus()).isEqualTo(AssignmentStatus.DRAFT);
            verify(assignments, never()).saveAllAndFlush(anyIterable());
        }

        @Test
        void draftCanAlwaysBeDeletedButNotClosed() {
            when(assignments.findCourseIdById(1L)).thenReturn(Optional.of(10L));
            when(assignments.findStatusById(1L)).thenReturn(Optional.of(AssignmentStatus.DRAFT));
            when(assignments.findById(1L)).thenReturn(Optional.of(assignment(1L, "Lab 1", AssignmentStatus.DRAFT, course)));

            assertStatus(() -> service.close(1L, TEACHER), HttpStatus.BAD_REQUEST, ErrorCodes.ASSIGNMENT_IS_DRAFT);
            service.delete(1L, TEACHER);

            verify(tasks, never()).findAssignmentIdsWithActivity(anyCollection());
            verify(assignments).deleteById(1L);
        }
    }

    /** With the real AccessControlService: MANAGE_ASSIGNMENTS is never granted to a student, enrolled or not. */
    @Test
    void studentCannotSeeOrPublishDrafts() {
        UserRepository users = mock(UserRepository.class);
        CourseRepository courses = mock(CourseRepository.class);
        EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
        AccessControlService realAccess = new AccessControlService(users, courses, mock(DocumentRepository.class),
                enrollments, audit);
        AssignmentService guarded = new AssignmentService(assignments, tasks, courses, mock(DocumentRepository.class),
                realAccess, audit, mock(AssignmentEvents.class), false);
        User student = User.builder().id(1L).email("student@uni.kz").role(Role.ROLE_STUDENT).build();
        when(users.findByEmail("student@uni.kz")).thenReturn(Optional.of(student));
        when(courses.findWithTeacherById(10L)).thenReturn(Optional.of(course));
        when(enrollments.existsByStudentIdAndCourseId(1L, 10L)).thenReturn(true);
        Assignment draft = assignment(1L, "Lab 1", AssignmentStatus.DRAFT, course);
        when(assignments.findById(1L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> guarded.publish(1L, "student@uni.kz")).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> guarded.publishAll(10L, List.of(1L), "student@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> guarded.drafts(10L, "student@uni.kz", Pageable.unpaged()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(draft.getStatus()).isEqualTo(AssignmentStatus.DRAFT);
        verify(tasks, never()).fanOut(anyLong());
        verify(assignments, never()).findDraftViewsByCourseId(anyLong(), any());
    }

    /** The upload notice counts drafts for the teacher; a student polling the same document never learns of them. */
    @Test
    void statusCountsDraftsForTheTeacherOnly() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentService documentService = new DocumentService(documents, tasks, assignments, access,
                mock(StorageService.class), mock(DocumentParserService.class), mock(DocumentAnalysisService.class),
                mock(AiClientService.class), mock(DocumentChunkService.class), audit,
                mock(DocumentClassificationService.class), mock(DocumentProcessingService.class),
                mock(BatchImportRepository.class), mock(AssignmentService.class),
                new DocumentPrivacyService(new PrivacyProperties(null, null)));
        Document syllabus = Document.builder().id(50L).aiStatus(AiStatus.MANUAL).category(DocumentCategory.SYLLABUS)
                .build();
        User student = User.builder().id(1L).email("student@uni.kz").role(Role.ROLE_STUDENT).build();
        when(access.currentUser("student@uni.kz")).thenReturn(student);
        when(access.authorizeDocument(any(User.class), eq(50L), eq(Permission.VIEW_DOCUMENT))).thenReturn(syllabus);
        when(assignments.countByStatusForSourceDocumentIds(List.of(50L))).thenReturn(List.of(
                new Object[]{50L, AssignmentStatus.DRAFT, 2L}, new Object[]{50L, AssignmentStatus.ACTIVE, 1L}));

        DocumentStatusResponse forTeacher = documentService.status(50L, TEACHER);
        DocumentStatusResponse forStudent = documentService.status(50L, "student@uni.kz");

        assertThat(forTeacher.assignmentsCount()).isEqualTo(3);
        assertThat(forTeacher.draftsCount()).isEqualTo(2);
        assertThat(forStudent.assignmentsCount()).isEqualTo(1);
        assertThat(forStudent.draftsCount()).isZero();
        verify(audit, never()).record(anyString(), eq(AuditService.ASSIGNMENT_PUBLISHED), anyString());
    }
}
