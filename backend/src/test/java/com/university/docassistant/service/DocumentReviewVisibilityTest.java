package com.university.docassistant.service;

import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.DocumentStatusItem;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentType;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.OcrStatus;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.BatchImportRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentChunkRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentRepository.StatusRow;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fix 2: (A) a scan not yet checked for personal data (PDF/image without recognized text) keeps its original closed to
 * students like one with personal data; (B) students never see a document the AI or the OCR failed on, until the
 * teacher sets its type by hand. With the real AccessControlService and DocumentService; the SQL side of rule B is
 * checked by query text here, parsed in RepositoryJpqlTest and run in AssignmentLifecyclePostgresTest (opt-in).
 */
class DocumentReviewVisibilityTest {

    private static final String TEXT = "Лекция 3. Сортировки: пузырьком, вставками, слиянием и быстрая. "
            + "Сложность по времени и памяти, устойчивость, примеры на массивах.";

    private final UserRepository users = mock(UserRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final AssignmentRepository assignments = mock(AssignmentRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final StorageService storage = mock(StorageService.class);
    private final DocumentPrivacyService privacy = new DocumentPrivacyService(new PrivacyProperties(true, List.of()));
    private final AccessControlService access = new AccessControlService(users, courses, documents, enrollments, audit);
    private final DocumentService service = new DocumentService(documents, mock(TaskRepository.class), assignments,
            access, storage, mock(DocumentParserService.class), mock(DocumentAnalysisService.class),
            mock(AiClientService.class), mock(DocumentChunkService.class), audit,
            mock(DocumentClassificationService.class), mock(DocumentProcessingService.class),
            mock(BatchImportRepository.class), mock(AssignmentService.class), privacy);

    private final User teacher = User.builder().id(2L).email("teacher@uni.kz").role(Role.ROLE_TEACHER).build();
    private final User student = User.builder().id(1L).email("student@uni.kz").role(Role.ROLE_STUDENT).build();
    private final User admin = User.builder().id(4L).email("dean@uni.kz").role(Role.ROLE_ADMIN).build();
    private final Course course = Course.builder().id(10L).title("Algorithms").teacher(teacher).build();

    private Document document;

    @BeforeEach
    void setUp() {
        for (User user : List.of(teacher, student, admin)) {
            when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        }
        when(enrollments.existsByStudentIdAndCourseId(1L, 10L)).thenReturn(true);
        when(enrollments.findCourseIdsByStudentId(1L)).thenReturn(List.of(10L));
        when(courses.findIdsByTeacherId(2L)).thenReturn(List.of(10L));
        when(courses.findAllIds()).thenReturn(List.of(10L));
        when(courses.findWithTeacherById(10L)).thenReturn(Optional.of(course));
        // A scan as stored by an upload with OCR switched off (the default): no text, no OCR status, nothing masked.
        document = Document.builder().id(50L).originalFileName("order-scan.pdf").storedFileName("stored")
                .contentType("application/pdf").fileSize(10L).category(DocumentCategory.OTHER)
                .type(DocumentType.OTHER).visibility(DocumentVisibility.COURSE_STUDENTS).aiStatus(AiStatus.MANUAL)
                .course(course).owner(teacher).uploadedAt(LocalDateTime.now()).piiCount(0).build();
        when(documents.findWithOwnerById(50L)).thenReturn(Optional.of(document));
        when(storage.load("stored")).thenReturn(new ByteArrayResource(new byte[]{1}));
    }

    private static void assertForbidden(Runnable call, String code, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex.getCode()).isEqualTo(code);
            if (message != null) {
                assertThat(ex.getMessage()).startsWith(message);
            }
        });
    }

    private void recognized(String text, int piiCount) {
        document.setOcrStatus(OcrStatus.DONE);
        document.setTextFromOcr(true);
        document.setExtractedText(text);
        document.setMaskedText(privacy.mask(text, null, null).text());
        document.setTextSnippet(DocumentParserService.snippet(document.getMaskedText()));
        document.setPiiCount(piiCount);
    }

    /** (A) US-09 for scans: unknown personal data counts as personal data. */
    @Nested
    class UncheckedScan {

        @Test
        void studentCannotOpenTheOriginalWhileOcrIsOffPendingRunningOrFailed() {
            for (OcrStatus status : new OcrStatus[]{null, OcrStatus.PENDING, OcrStatus.RUNNING, OcrStatus.FAILED}) {
                document.setOcrStatus(status);

                assertForbidden(() -> service.view(50L, "student@uni.kz"), ErrorCodes.PII_ORIGINAL_RESTRICTED,
                        "Скан ещё не проверен на личные данные");
                assertForbidden(() -> service.download(50L, "student@uni.kz"), ErrorCodes.PII_ORIGINAL_RESTRICTED,
                        "Скан ещё не проверен на личные данные");
            }
            verify(storage, never()).load(anyString());
            verify(audit, org.mockito.Mockito.atLeastOnce()).record(eq("student@uni.kz"),
                    eq(AuditService.ACCESS_DENIED), contains("scan not checked for personal data"));
        }

        @Test
        void imageScansAndAlmostEmptyTextLayersCountToo() {
            document.setContentType("image/jpeg");
            assertForbidden(() -> service.view(50L, "student@uni.kz"), ErrorCodes.PII_ORIGINAL_RESTRICTED, null);

            document.setContentType("application/pdf");
            document.setExtractedText("Стр. 1");
            document.setTextSnippet("Стр. 1");
            assertForbidden(() -> service.view(50L, "student@uni.kz"), ErrorCodes.PII_ORIGINAL_RESTRICTED, null);
        }

        @Test
        void studentOpensTheOriginalAfterASuccessfulOcrWithoutPersonalData() {
            recognized(TEXT, 0);

            assertThat(service.view(50L, "student@uni.kz").document()).isSameAs(document);
            assertThat(service.get(50L, "student@uni.kz").piiUnknown()).isFalse();
        }

        @Test
        void afterOcrFoundPersonalDataTheUsualRuleApplies() {
            recognized(TEXT + " Староста: ИИН 990101300013.", 1);

            assertForbidden(() -> service.download(50L, "student@uni.kz"), ErrorCodes.PII_ORIGINAL_RESTRICTED,
                    "В оригинале документа есть персональные данные");
        }

        @Test
        void teacherAllowingTheOriginalOpensItForStudents() {
            service.setOriginalAllowedForStudents(50L, true, "teacher@uni.kz");

            assertThat(service.download(50L, "student@uni.kz").document()).isSameAs(document);
            verify(audit).record(eq("student@uni.kz"), eq(AuditService.PII_ORIGINAL_VIEWED),
                    contains("scan not checked for personal data"));
        }

        @Test
        void teacherAndAdminOpenItAndSeeTheFlag() {
            assertThat(service.view(50L, "teacher@uni.kz").document()).isSameAs(document);
            assertThat(service.download(50L, "dean@uni.kz").document()).isSameAs(document);
            verify(audit).record(eq("teacher@uni.kz"), eq(AuditService.PII_ORIGINAL_VIEWED), contains("view"));

            assertThat(service.get(50L, "teacher@uni.kz").piiUnknown()).isTrue();
            assertThat(service.get(50L, "student@uni.kz").piiUnknown()).isTrue();
        }

        @Test
        void textDocumentsAreNotScans() {
            document.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

            service.view(50L, "student@uni.kz");
            assertThat(service.get(50L, "teacher@uni.kz").piiUnknown()).isFalse();
        }

        @Test
        void withPrivacySwitchedOffNothingIsUnknown() {
            DocumentPrivacyService off = new DocumentPrivacyService(new PrivacyProperties(false, List.of()));

            assertThat(off.isPiiUnknown("application/pdf", null, null)).isFalse();
            assertThat(privacy.isPiiUnknown("application/pdf", null, null)).isTrue();
            assertThat(privacy.isPiiUnknown("application/pdf", OcrStatus.DONE, null)).isFalse();
        }
    }

    /** (B) Rule C for failures. */
    @Nested
    class FailedDocuments {

        @Test
        void hiddenFromStudentsExactlyWhenAiOrOcrFailedOrStillWorks() {
            for (AiStatus ai : AiStatus.values()) {
                for (OcrStatus ocr : new OcrStatus[]{null, OcrStatus.PENDING, OcrStatus.RUNNING, OcrStatus.DONE,
                        OcrStatus.FAILED}) {
                    boolean expected = ai == AiStatus.PENDING || ai == AiStatus.PROCESSING || ai == AiStatus.FAILED
                            || (ocr == OcrStatus.FAILED && ai != AiStatus.MANUAL);
                    assertThat(AccessControlService.isHiddenFromStudents(ai, ocr)).as("%s / %s", ai, ocr)
                            .isEqualTo(expected);
                }
            }
        }

        @Test
        void studentCannotOpenAFailedDocumentByIdButTeacherAndAdminCan() {
            recognized(TEXT, 0);
            document.setAiStatus(AiStatus.FAILED);
            assertForbidden(() -> service.get(50L, "student@uni.kz"), ErrorCodes.ACCESS_DENIED, null);
            assertForbidden(() -> service.text(50L, "student@uni.kz"), ErrorCodes.ACCESS_DENIED, null);

            document.setAiStatus(AiStatus.DONE);
            document.setOcrStatus(OcrStatus.FAILED); // "Нужна проверка", typed OTHER from the file name.
            assertForbidden(() -> service.get(50L, "student@uni.kz"), ErrorCodes.ACCESS_DENIED, null);
            assertForbidden(() -> service.download(50L, "student@uni.kz"), ErrorCodes.ACCESS_DENIED, null);

            assertThat(service.get(50L, "teacher@uni.kz").id()).isEqualTo(50L);
            assertThat(service.get(50L, "dean@uni.kz").id()).isEqualTo(50L);
        }

        @Test
        void typeSetByHandMakesTheScanVisibleAccordingToItsVisibility() {
            document.setAiStatus(AiStatus.DONE);
            document.setOcrStatus(OcrStatus.FAILED);
            doAnswer(invocation -> {
                document.setCategory(invocation.getArgument(1));
                document.setAiStatus(invocation.getArgument(3));
                return 1;
            }).when(documents).updateClassification(eq(50L), any(), any(), any());
            assertForbidden(() -> service.get(50L, "student@uni.kz"), ErrorCodes.ACCESS_DENIED, null);

            service.setCategory(50L, DocumentCategory.LECTURE, "teacher@uni.kz");

            assertThat(service.get(50L, "student@uni.kz").category()).isEqualTo(DocumentCategory.LECTURE);
            // Still a scan without text: the original stays closed (A).
            assertForbidden(() -> service.view(50L, "student@uni.kz"), ErrorCodes.PII_ORIGINAL_RESTRICTED, null);

            document.setVisibility(DocumentVisibility.TEACHER_ONLY);
            assertForbidden(() -> service.get(50L, "student@uni.kz"), ErrorCodes.ACCESS_DENIED, null);
        }

        @Test
        void statusPollLeavesFailedDocumentsOutForStudentsOnly() {
            when(documents.findStatusRows(any())).thenReturn(List.of(
                    new StatusRow(1L, 10L, DocumentVisibility.COURSE_STUDENTS, AiStatus.DONE, DocumentCategory.LECTURE, 0.9, null, false),
                    new StatusRow(2L, 10L, DocumentVisibility.COURSE_STUDENTS, AiStatus.FAILED, DocumentCategory.OTHER, null, null, false),
                    new StatusRow(3L, 10L, DocumentVisibility.COURSE_STUDENTS, AiStatus.DONE, DocumentCategory.OTHER, 0.4, OcrStatus.FAILED, false),
                    new StatusRow(4L, 10L, DocumentVisibility.COURSE_STUDENTS, AiStatus.MANUAL, DocumentCategory.LECTURE, null, OcrStatus.FAILED, false)));

            assertThat(service.statuses(List.of(1L, 2L, 3L, 4L), "student@uni.kz"))
                    .extracting(DocumentStatusItem::id).containsExactly(1L, 4L);
            assertThat(service.statuses(List.of(1L, 2L, 3L, 4L), "teacher@uni.kz"))
                    .extracting(DocumentStatusItem::id).containsExactly(1L, 2L, 3L, 4L);
        }

        @Test
        void listAsksTheDatabaseForStudentVisibleRowsAndNeedsReviewIsForTheTeacher() {
            when(documents.findSummariesInCourses(any(), anyBoolean(), any(), anyBoolean(), any()))
                    .thenReturn(Page.empty());

            service.list("student@uni.kz", 10L, false, PageRequest.of(0, 10));
            verify(documents).findSummariesInCourses(List.of(10L), false, 10L, false, PageRequest.of(0, 10));

            assertForbidden(() -> service.list("student@uni.kz", 10L, true, PageRequest.of(0, 10)),
                    ErrorCodes.COURSE_NOT_ENROLLED, null);
            verify(audit).record(eq("student@uni.kz"), eq(AuditService.ACCESS_DENIED), contains("MANAGE_COURSE"));

            service.list("teacher@uni.kz", 10L, true, PageRequest.of(0, 10));
            verify(documents).findSummariesInCourses(List.of(10L), true, 10L, true, PageRequest.of(0, 10));
        }

        @Test
        void everyStudentQueryCarriesTheRule() {
            assertThat(queriesOf(DocumentRepository.class, "findSummariesInCourses"))
                    .hasSize(2).allSatisfy(jpql -> assertThat(jpql).contains(DocumentRepository.VISIBLE_TO_STUDENTS));
            assertThat(queriesOf(DocumentRepository.class, "searchVisibleIds"))
                    .hasSize(2).allSatisfy(sql -> assertThat(sql).contains(DocumentRepository.VISIBLE_TO_STUDENTS_SQL));
            assertThat(queriesOf(DocumentChunkRepository.class, "searchInCourses"))
                    .hasSize(1).allSatisfy(sql -> assertThat(sql).contains(DocumentRepository.VISIBLE_TO_STUDENTS_SQL));
            assertThat(DocumentRepository.VISIBLE_TO_STUDENTS).contains("OcrStatus.FAILED", "AiStatus.DONE", "AiStatus.MANUAL");
            assertThat(DocumentRepository.VISIBLE_TO_STUDENTS_SQL).contains("ocr_status <> 'FAILED'", "ai_status = 'DONE'");
        }

        private List<String> queriesOf(Class<?> repository, String name) {
            return Arrays.stream(repository.getMethods())
                    .filter((Method method) -> method.getName().equals(name))
                    .map(method -> method.getAnnotation(Query.class))
                    .flatMap(query -> java.util.stream.Stream.of(query.value(), query.countQuery()))
                    .filter(text -> !text.isEmpty())
                    .toList();
        }
    }
}
