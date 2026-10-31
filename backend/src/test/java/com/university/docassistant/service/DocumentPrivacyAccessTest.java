package com.university.docassistant.service;

import com.university.docassistant.config.AiProperties;
import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.DocumentResponse;
import com.university.docassistant.dto.DocumentTextResponse;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.BatchImportRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentChunkRepository;
import com.university.docassistant.repository.DocumentChunkRepository.ChunkHit;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-09 step 2: students only ever receive masked text; the original file with personal data only when the course
 * teacher allows it; every opening of such an original is audited. With the real AccessControlService, so every
 * 403 is the real decision.
 */
class DocumentPrivacyAccessTest {

    private static final String IIN = "990101300013";
    private static final String ORIGINAL = "Список группы ИНФ-345 на весенний семестр: Иванов А., ИИН 990101300013, "
            + "тел. 87011234567. Староста группы отвечает за посещаемость.";

    private final UserRepository users = mock(UserRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final StorageService storage = mock(StorageService.class);
    private final DocumentPrivacyService privacy = new DocumentPrivacyService(new PrivacyProperties(true, List.of()));
    private final AccessControlService access = new AccessControlService(users, courses, documents, enrollments, audit);
    private final DocumentService service = new DocumentService(documents, mock(TaskRepository.class),
            mock(AssignmentRepository.class), access, storage, mock(DocumentParserService.class),
            mock(DocumentAnalysisService.class), mock(AiClientService.class), mock(DocumentChunkService.class), audit,
            mock(DocumentClassificationService.class), mock(DocumentProcessingService.class),
            mock(BatchImportRepository.class), mock(AssignmentService.class), privacy);

    private final User teacher = User.builder().id(2L).email("teacher@uni.kz").role(Role.ROLE_TEACHER).build();
    private final User otherTeacher = User.builder().id(3L).email("other@uni.kz").role(Role.ROLE_TEACHER).build();
    private final User student = User.builder().id(1L).email("student@uni.kz").role(Role.ROLE_STUDENT).build();
    private final User admin = User.builder().id(4L).email("dean@uni.kz").role(Role.ROLE_ADMIN).build();
    private final Course course = Course.builder().id(10L).title("Algorithms").teacher(teacher).build();

    private Document document;

    @BeforeEach
    void setUp() {
        for (User user : List.of(teacher, otherTeacher, student, admin)) {
            when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        }
        when(enrollments.existsByStudentIdAndCourseId(1L, 10L)).thenReturn(true);
        document = Document.builder().id(50L).originalFileName("group-list.pdf").storedFileName("stored")
                .contentType("application/pdf").fileSize(10L).category(DocumentCategory.OTHER)
                .visibility(DocumentVisibility.COURSE_STUDENTS).aiStatus(AiStatus.DONE).course(course).owner(teacher)
                .uploadedAt(LocalDateTime.now())
                .extractedText(ORIGINAL).maskedText(privacy.mask(ORIGINAL, null, null).text())
                .piiCount(2).piiTypes("IIN,PHONE").build();
        document.setTextSnippet(DocumentParserService.snippet(document.getMaskedText()));
        when(documents.findWithOwnerById(50L)).thenReturn(Optional.of(document));
        when(storage.load("stored")).thenReturn(new ByteArrayResource(new byte[]{1}));
    }

    private static void assertForbidden(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            if (code != null) {
                assertThat(ex.getCode()).isEqualTo(code);
            }
        });
    }

    @Nested
    class Student {

        @Test
        void getsTheMaskedTextNeverTheOriginal() {
            DocumentTextResponse text = service.text(50L, "student@uni.kz");

            assertThat(text.masked()).isTrue();
            assertThat(text.piiCount()).isEqualTo(2);
            assertThat(text.text()).doesNotContain(IIN).doesNotContain("87011234567").contains("[ИИН скрыт]");
        }

        @Test
        void getsMaskedTextEvenBeforeTheBackfillReachedTheDocument() {
            document.setMaskedText(null);

            assertThat(service.text(50L, "student@uni.kz").text()).doesNotContain(IIN).contains("[ИИН скрыт]");
        }

        @Test
        void cannotOpenOrDownloadAnOriginalWithPersonalData() {
            assertForbidden(() -> service.view(50L, "student@uni.kz"), ErrorCodes.PII_ORIGINAL_RESTRICTED);
            assertForbidden(() -> service.download(50L, "student@uni.kz"), ErrorCodes.PII_ORIGINAL_RESTRICTED);

            verify(storage, never()).load(anyString());
            verify(audit, never()).record(anyString(), eq(AuditService.PII_ORIGINAL_VIEWED), anyString());
        }

        @Test
        void downloadsTheOriginalOnceTheTeacherAllowedIt() {
            service.setOriginalAllowedForStudents(50L, true, "teacher@uni.kz");

            assertThat(service.download(50L, "student@uni.kz").document()).isSameAs(document);
            verify(audit).record(eq("teacher@uni.kz"), eq(AuditService.PII_ORIGINAL_ALLOWED), contains("Document 50"));
            verify(audit).record(eq("student@uni.kz"), eq(AuditService.PII_ORIGINAL_VIEWED), contains("download"));
            // The text stays masked for students even then.
            assertThat(service.text(50L, "student@uni.kz").text()).doesNotContain(IIN);
        }

        @Test
        void denyTakesThePermissionBack() {
            service.setOriginalAllowedForStudents(50L, true, "teacher@uni.kz");
            service.setOriginalAllowedForStudents(50L, false, "teacher@uni.kz");

            assertForbidden(() -> service.download(50L, "student@uni.kz"), ErrorCodes.PII_ORIGINAL_RESTRICTED);
            verify(audit).record(eq("teacher@uni.kz"), eq(AuditService.PII_ORIGINAL_DENIED), anyString());
        }

        @Test
        void cannotAllowTheOriginalThemselvesAndNeitherCanAnotherTeacher() {
            assertForbidden(() -> service.setOriginalAllowedForStudents(50L, true, "student@uni.kz"), null);
            assertForbidden(() -> service.setOriginalAllowedForStudents(50L, true, "other@uni.kz"), null);

            assertThat(document.isOriginalAllowedForStudents()).isFalse();
            verify(documents, never()).save(any());
        }

        @Test
        void documentWithoutPersonalDataIsOpenAsBefore() {
            document.setPiiCount(0);

            service.view(50L, "student@uni.kz");

            verify(storage).load("stored");
            verify(audit, never()).record(anyString(), eq(AuditService.PII_ORIGINAL_VIEWED), anyString());
        }

        @Test
        void listResponseHidesWhichKindsOfPersonalDataThereAre() {
            when(documents.findWithOwnerById(50L)).thenReturn(Optional.of(document));

            DocumentResponse forStudent = service.get(50L, "student@uni.kz");
            DocumentResponse forTeacher = service.get(50L, "teacher@uni.kz");

            assertThat(forStudent.piiCount()).isEqualTo(2);
            assertThat(forStudent.piiTypes()).isNull();
            assertThat(forStudent.originalAllowedForStudents()).isFalse();
            assertThat(forTeacher.piiTypes()).isEqualTo("IIN,PHONE");
        }
    }

    @Nested
    class TeacherAndAdmin {

        @Test
        void teacherGetsTheOriginalTextAndFileAndTheOpeningIsAudited() {
            DocumentTextResponse text = service.text(50L, "teacher@uni.kz");
            service.view(50L, "teacher@uni.kz");

            assertThat(text.masked()).isFalse();
            assertThat(text.text()).isEqualTo(ORIGINAL);
            ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
            verify(audit).record(eq("teacher@uni.kz"), eq(AuditService.PII_ORIGINAL_VIEWED), details.capture());
            assertThat(details.getValue()).contains("Document 50", "view", "IIN,PHONE");
        }

        @Test
        void adminDownloadIsAuditedToo() {
            service.download(50L, "dean@uni.kz");

            verify(audit).record(eq("dean@uni.kz"), eq(AuditService.PII_ORIGINAL_VIEWED), contains("download"));
        }
    }

    /** Search and Q&A read only text_tsv and chunks, both built from masked_text: an IIN is not in them. */
    @Nested
    class SearchAndQa {

        @Test
        void searchAndChunkQueriesNeverReadTheOriginalText() {
            List<String> queries = Arrays.stream(DocumentRepository.class.getMethods())
                    .filter(method -> method.getName().equals("searchVisibleIds"))
                    .map(method -> method.getAnnotation(Query.class).value())
                    .toList();
            List<String> chunkQueries = Arrays.stream(DocumentChunkRepository.class.getMethods())
                    .map((Method method) -> method.getAnnotation(Query.class))
                    .filter(query -> query != null)
                    .map(Query::value)
                    .toList();

            assertThat(queries).hasSize(1).allSatisfy(sql ->
                    assertThat(sql).contains("text_tsv").doesNotContain("extracted_text"));
            assertThat(chunkQueries).isNotEmpty().allSatisfy(sql -> assertThat(sql).doesNotContain("extracted_text"));
        }

        @Test
        void qaContextSentToTheAiHasNoIinNotEvenInTheFileName() {
            DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
            AiClientService ai = mock(AiClientService.class);
            DocumentService viewable = mock(DocumentService.class);
            DocumentQnaService qna = new DocumentQnaService(viewable, chunks, access, ai,
                    new AiProperties(true, "k", "m", "http://x", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 1, 2, 1),
                    privacy);
            document.setTextSnippet(DocumentParserService.snippet(document.getMaskedText()));
            when(viewable.getViewable(50L, "student@uni.kz")).thenReturn(document);
            when(ai.isConfigured()).thenReturn(true);
            when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Ответ"));
            // The chunk as DocumentChunkService cut it: from the masked text.
            ChunkHit hit = new ChunkHit() {
                public Long getDocumentId() { return 50L; }
                public String getDocumentName() { return "Иванов_" + IIN + ".pdf"; }
                public Integer getChunkIndex() { return 0; }
                public Integer getStartOffset() { return 0; }
                public Integer getEndOffset() { return 60; }
                public String getContent() { return document.getMaskedText(); }
                public Double getScore() { return 1.0; }
            };
            when(chunks.searchInDocument(eq(50L), anyString(), anyInt())).thenReturn(List.of(hit));
            when(chunks.firstChunks(anyLong(), anyInt())).thenReturn(List.of(hit));

            qna.askDocument(50L, "Какой ИИН у Иванова?", "student@uni.kz");

            ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
            verify(ai).chat(systemPrompt.capture(), anyString());
            assertThat(systemPrompt.getValue()).doesNotContain(IIN).doesNotContain("87011234567")
                    .contains("[ИИН скрыт]", "Никогда не пытайся восстановить");
        }
    }

    /** Review fix: file names with personal data, and the Q&A question itself. */
    @Nested
    class FileNamesAndQuestions {

        private static final String NAME = "Иванов_ИИН_" + IIN + "_тел_87011234567.pdf";

        @BeforeEach
        void personalFileName() {
            document.setOriginalFileName(NAME);
            document.setOriginalAllowedForStudents(true);
        }

        @Test
        void studentSeesTheNameMaskedOnTheDocumentPageTextTabAndDownload() {
            assertThat(service.get(50L, "student@uni.kz").originalFileName())
                    .isEqualTo("Иванов_ИИН_[ИИН скрыт]_тел_[телефон скрыт].pdf");
            assertThat(service.text(50L, "student@uni.kz").originalFileName()).doesNotContain(IIN);
            assertThat(service.download(50L, "student@uni.kz").fileName()).doesNotContain(IIN).doesNotContain("87011234567");
            assertThat(service.view(50L, "student@uni.kz").fileName()).doesNotContain(IIN);
        }

        @Test
        void teacherAndAdminSeeTheRealName() {
            for (String email : List.of("teacher@uni.kz", "dean@uni.kz")) {
                assertThat(service.get(50L, email).originalFileName()).isEqualTo(NAME);
                assertThat(service.text(50L, email).originalFileName()).isEqualTo(NAME);
                assertThat(service.download(50L, email).fileName()).isEqualTo(NAME);
            }
        }

        @Test
        void questionIsMaskedBeforeTheAiAndSourcesShowTheNameByRole() {
            DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
            AiClientService ai = mock(AiClientService.class);
            DocumentService viewable = mock(DocumentService.class);
            DocumentQnaService qna = new DocumentQnaService(viewable, chunks, access, ai,
                    new AiProperties(true, "k", "m", "http://x", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 1, 2, 1),
                    privacy);
            when(viewable.getViewable(eq(50L), anyString())).thenReturn(document);
            when(ai.isConfigured()).thenReturn(true);
            when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Ответ [" + NAME + ", фрагмент 1]"));
            ChunkHit hit = new ChunkHit() {
                public Long getDocumentId() { return 50L; }
                public String getDocumentName() { return NAME; }
                public Integer getChunkIndex() { return 0; }
                public Integer getStartOffset() { return 0; }
                public Integer getEndOffset() { return 60; }
                public String getContent() { return document.getMaskedText(); }
                public Double getScore() { return 1.0; }
            };
            when(chunks.searchInDocument(eq(50L), anyString(), anyInt())).thenReturn(List.of(hit));

            var forStudent = qna.askDocument(50L, "Мой ИИН " + IIN + ", телефон +7 701 123 45 67 — где расписание?",
                    "student@uni.kz");
            var forTeacher = qna.askDocument(50L, "Где расписание?", "teacher@uni.kz");

            ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
            verify(ai, org.mockito.Mockito.times(2)).chat(anyString(), question.capture());
            assertThat(question.getAllValues().get(0)).doesNotContain(IIN).doesNotContain("701 123 45 67")
                    .contains("[ИИН скрыт]", "[телефон скрыт]", "где расписание?");
            assertThat(forStudent.sources()).singleElement()
                    .satisfies(source -> assertThat(source.documentName()).doesNotContain(IIN));
            assertThat(forTeacher.sources()).singleElement()
                    .satisfies(source -> assertThat(source.documentName()).isEqualTo(NAME));
        }

        @Test
        void teacherPhoneStaysReadableNextToTheirEmailOtherPhonesDoNot() {
            String text = "Преподаватель: teacher@uni.kz, +7 (702) 555-12-34. Староста: 87011234567.";

            String masked = privacy.mask(text, "teacher@uni.kz", "77025551234").text();

            assertThat(masked).contains("teacher@uni.kz", "+7 (702) 555-12-34").contains("Староста: [телефон скрыт]");
        }
    }
}
