package com.university.docassistant.service;

import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.DocumentStatusItem;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.BatchImportRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentRepository.StatusRow;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.service.storage.StorageService;
import com.university.docassistant.util.LikePatterns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** GET /documents/status?ids=: one answer for many documents, silently limited to what the user may view. */
class DocumentStatusBatchTest {

    private final UserRepository users = mock(UserRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final AssignmentRepository assignments = mock(AssignmentRepository.class);
    private final AccessControlService access = new AccessControlService(users, courses, documents, enrollments,
            mock(AuditService.class));
    private final DocumentService service = new DocumentService(documents, mock(TaskRepository.class), assignments,
            access, mock(StorageService.class), mock(DocumentParserService.class), mock(DocumentAnalysisService.class),
            mock(AiClientService.class), mock(DocumentChunkService.class), mock(AuditService.class),
            mock(DocumentClassificationService.class), mock(DocumentProcessingService.class),
            mock(BatchImportRepository.class), mock(AssignmentService.class),
            new DocumentPrivacyService(new PrivacyProperties(null, null)));

    // Course 10: a classified syllabus (1), a document still being classified (2), a teacher note (3).
    // Course 20: someone else's course (4). Id 99 does not exist.
    private final List<StatusRow> rows = List.of(
            new StatusRow(1L, 10L, DocumentVisibility.COURSE_STUDENTS, AiStatus.DONE, DocumentCategory.SYLLABUS, 0.96, null, false),
            new StatusRow(2L, 10L, DocumentVisibility.COURSE_STUDENTS, AiStatus.PENDING, DocumentCategory.OTHER, null, null, false),
            new StatusRow(3L, 10L, DocumentVisibility.TEACHER_ONLY, AiStatus.MANUAL, DocumentCategory.LECTURE, 0.5, null, false),
            new StatusRow(4L, 20L, DocumentVisibility.COURSE_STUDENTS, AiStatus.DONE, DocumentCategory.LECTURE, 0.8, null, false));

    @BeforeEach
    void setUp() {
        user(1L, "student@uni.kz", Role.ROLE_STUDENT);
        user(2L, "teacher@uni.kz", Role.ROLE_TEACHER);
        user(3L, "dean@uni.kz", Role.ROLE_ADMIN);
        when(enrollments.findCourseIdsByStudentId(1L)).thenReturn(List.of(10L));
        when(courses.findIdsByTeacherId(2L)).thenReturn(List.of(10L));
        when(courses.findAllIds()).thenReturn(List.of(10L, 20L));
        when(documents.findStatusRows(anyCollection())).thenReturn(rows);
        List<Object[]> counts = new ArrayList<>();
        counts.add(new Object[]{1L, 3L});
        when(assignments.countBySourceDocumentIds(anyCollection())).thenReturn(counts);
    }

    @Test
    void studentGetsOnlyVisibleDocumentsOfTheirCourseAndNothingPending() {
        List<DocumentStatusItem> result = service.statuses(List.of(1L, 2L, 3L, 4L, 99L), "student@uni.kz");

        assertThat(result).extracting(DocumentStatusItem::id).containsExactly(1L);
        DocumentStatusItem syllabus = result.get(0);
        assertThat(syllabus.aiStatus()).isEqualTo(AiStatus.DONE);
        assertThat(syllabus.aiConfidence()).isEqualTo(0.96);
        assertThat(syllabus.assignmentsCount()).isEqualTo(3);
        // The count query only asks about what the user may see.
        verify(assignments).countBySourceDocumentIds(List.of(1L));
    }

    @Test
    void teacherOfTheCourseSeesPendingAndTeacherOnlyButNotOtherCourses() {
        List<DocumentStatusItem> result = service.statuses(List.of(1L, 2L, 3L, 4L, 99L), "teacher@uni.kz");

        assertThat(result).extracting(DocumentStatusItem::id).containsExactly(1L, 2L, 3L);
        assertThat(result.get(1).aiStatus()).isEqualTo(AiStatus.PENDING);
        // Confidence only for an AI result that is DONE, like the single-document endpoint.
        assertThat(result.get(1).aiConfidence()).isNull();
        assertThat(result.get(2).aiConfidence()).isNull();
        assertThat(result.get(1).assignmentsCount()).isZero();
        assertThat(result.get(0).assignmentsCount()).as("the same count as a student's").isEqualTo(3);
    }

    @Test
    void adminSeesEveryExistingDocument() {
        assertThat(service.statuses(List.of(1L, 2L, 3L, 4L, 99L), "dean@uni.kz"))
                .extracting(DocumentStatusItem::id).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void moreThanFiftyIdsIsABadRequestWithoutTouchingTheDatabase() {
        List<Long> ids = LongStream.rangeClosed(1, 51).boxed().toList();

        assertThatThrownBy(() -> service.statuses(ids, "teacher@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.TOO_MANY_IDS);
                });
        verifyNoInteractions(documents, assignments);
    }

    @Test
    void duplicatesCountOnceAndEmptyInputAsksNothing() {
        List<Long> fiftyWithDuplicates = new ArrayList<>(LongStream.rangeClosed(1, 50).boxed().toList());
        fiftyWithDuplicates.addAll(List.of(1L, 2L, 3L));

        assertThat(service.statuses(fiftyWithDuplicates, "dean@uni.kz")).hasSize(4);
        assertThat(service.statuses(List.of(), "dean@uni.kz")).isEmpty();
        assertThat(service.statuses(null, "dean@uni.kz")).isEmpty();
    }

    @Test
    void searchPatternsEscapeWildcardsAndIgnoreShortTerms() {
        assertThat(LikePatterns.contains("  Ив ")).isEqualTo("%ив%");
        assertThat(LikePatterns.contains("50%_off\\")).isEqualTo("%50\\%\\_off\\\\%");
        assertThat(LikePatterns.contains("a")).isNull();
        assertThat(LikePatterns.contains(null)).isNull();
    }

    private void user(long id, String email, Role role) {
        when(users.findByEmail(email)).thenReturn(Optional.of(User.builder().id(id).email(email).role(role).build()));
    }
}
