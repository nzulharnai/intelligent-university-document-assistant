package com.university.docassistant.qa;

import com.university.docassistant.service.DocumentPrivacyService;
import com.university.docassistant.config.PrivacyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.university.docassistant.config.AiProperties;
import com.university.docassistant.dto.TaskExtractDto;
import com.university.docassistant.dto.UploadDtos.BatchUploadResponse;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.BatchImport;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentClassification;
import com.university.docassistant.entity.DocumentType;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.BatchImportRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentClassificationRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentRepository.AnalysisSource;
import com.university.docassistant.repository.DocumentRepository.ClassificationSource;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import com.university.docassistant.service.AiClientService;
import com.university.docassistant.service.AiTaskExtractionService;
import com.university.docassistant.service.AssignmentService;
import com.university.docassistant.service.AuditService;
import com.university.docassistant.service.DocumentAnalysisService;
import com.university.docassistant.service.DocumentChunkService;
import com.university.docassistant.service.DocumentClassificationService;
import com.university.docassistant.service.DocumentOcrService;
import com.university.docassistant.service.DocumentParserService;
import com.university.docassistant.service.DocumentParserService.ExtractedText;
import com.university.docassistant.service.DocumentProcessingService;
import com.university.docassistant.service.DocumentService;
import com.university.docassistant.service.storage.StorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sprint 2 acceptance cases (QA-UPL-04, QA-ML-05, QA-UPL-06 and the security rules) as unit tests. The AI is a
 * Mockito mock of AiClientService - Groq is never called; everything between the controller and the repositories
 * (access control, validation, ZIP extraction, classification, deadline extraction) is the real code.
 */
class Sprint2QaTest {

    private static final String TEACHER_EMAIL = "teacher@uni.kz";
    private static final String STUDENT_EMAIL = "student@uni.kz";
    private static final long COURSE_ID = 345L;

    private final User teacher = User.builder().id(2L).email(TEACHER_EMAIL).fullName("Преподаватель")
            .role(Role.ROLE_TEACHER).build();
    private final User student = User.builder().id(1L).email(STUDENT_EMAIL).fullName("Студент")
            .role(Role.ROLE_STUDENT).build();
    private final Course inf345 = Course.builder().id(COURSE_ID).code("INF345").title("Algorithms").teacher(teacher)
            .build();

    // Repositories and infrastructure are mocks; services below are real.
    private final UserRepository users = mock(UserRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final BatchImportRepository imports = mock(BatchImportRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final DocumentParserService parser = mock(DocumentParserService.class);
    private final AuditService audit = mock(AuditService.class);
    private final AiClientService ai = mock(AiClientService.class);
    private final DocumentClassificationService classifier = mock(DocumentClassificationService.class);
    private final DocumentProcessingService processing = mock(DocumentProcessingService.class);

    private final AccessControlService access = new AccessControlService(users, courses, documents, enrollments, audit);
    private final DocumentService documentService = new DocumentService(documents, mock(TaskRepository.class),
            mock(AssignmentRepository.class), access, storage, parser, mock(DocumentAnalysisService.class), ai,
            mock(DocumentChunkService.class), audit, classifier, processing, imports, mock(AssignmentService.class),
            new DocumentPrivacyService(new PrivacyProperties(null, null)));

    private final List<Document> savedDocuments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(users.findByEmail(TEACHER_EMAIL)).thenReturn(Optional.of(teacher));
        when(users.findByEmail(STUDENT_EMAIL)).thenReturn(Optional.of(student));
        when(courses.findWithTeacherById(COURSE_ID)).thenReturn(Optional.of(inf345));
        when(enrollments.existsByStudentIdAndCourseId(student.getId(), COURSE_ID)).thenReturn(true);
        when(ai.isConfigured()).thenReturn(true);
        when(parser.extractText(any(InputStream.class), anyString(), anyString()))
                .thenReturn(new ExtractedText("Текст учебного документа", false));
        when(storage.store(any(byte[].class))).thenReturn("stored-name");
        AtomicLong ids = new AtomicLong(1000);
        when(documents.save(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId(ids.incrementAndGet());
            savedDocuments.add(document);
            return document;
        });
        when(imports.save(any(BatchImport.class))).thenAnswer(invocation -> {
            BatchImport batchImport = invocation.getArgument(0);
            batchImport.setId(77L);
            return batchImport;
        });
    }

    @Nested
    @DisplayName("QA-UPL-04: course-bound multi-file upload")
    class QaUpl04 {

        @Test
        @DisplayName("teacher of INF345 uploads 5 valid files: 5 PENDING documents, 5 jobs queued, no AI call in the request")
        void teacherUploadsFiveFiles() throws IOException {
            List<MultipartFile> files = List.of(
                    file("syllabus.pdf", pdf()), file("lecture1.pdf", pdf()), file("lab1.docx", docx()),
                    file("diagram.png", png()), file("notes.txt", "Конспект лекции".getBytes(StandardCharsets.UTF_8)));

            BatchUploadResponse response = documentService.uploadMultiple(files, null, null, COURSE_ID, TEACHER_EMAIL);

            assertThat(response.uploaded()).hasSize(5);
            assertThat(response.skipped()).isEmpty();
            assertThat(savedDocuments).hasSize(5).allSatisfy(document -> {
                assertThat(document.getCourse()).isSameAs(inf345);
                assertThat(document.getAiStatus()).isEqualTo(AiStatus.PENDING);
                assertThat(document.getCategory()).isEqualTo(DocumentCategory.OTHER);
            });
            assertThat(savedDocuments).extracting(Document::getContentType).containsExactly(
                    "application/pdf", "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/png", "text/plain");
            savedDocuments.forEach(document -> verify(processing).processAsync(document.getId()));
            verify(processing, times(5)).processAsync(anyLong());
            // The upload never waits for the AI.
            verifyNoInteractions(classifier);
            verify(ai, never()).chat(anyString(), anyString());
            verify(ai, never()).chatJson(anyString(), anyString());
        }

        @Test
        @DisplayName("student uploading to INF345 gets 403, nothing is stored, ACCESS_DENIED is audited")
        void studentIsRefused() throws IOException {
            List<MultipartFile> files = List.of(file("solution.pdf", pdf()));

            assertThatThrownBy(() -> documentService.uploadMultiple(files, null, null, COURSE_ID, STUDENT_EMAIL))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

            verifyNoInteractions(storage, processing);
            verify(documents, never()).save(any());
            verify(audit).record(eq(STUDENT_EMAIL), eq(AuditService.ACCESS_DENIED), startsWith("UPLOAD_DOCUMENT"));
        }
    }

    @Nested
    @DisplayName("QA-ML-05: background classification and deadline extraction")
    class QaMl05 {

        private static final long SYLLABUS_ID = 501L;
        private static final long PRACTICE_ID = 502L;

        private final DocumentClassificationRepository classificationRows = mock(DocumentClassificationRepository.class);
        private final AssignmentService assignments = mock(AssignmentService.class);
        private final AiProperties properties = new AiProperties(true, "test-key", "openai/gpt-oss-120b",
                "http://ai.test", Duration.ofSeconds(1), Duration.ofSeconds(1), 60_000, 1, 1, 1, 1);
        private final DocumentClassificationService realClassifier =
                new DocumentClassificationService(ai, classificationRows, documents, new ObjectMapper(),
                        new DocumentPrivacyService(new PrivacyProperties(null, null)));
        private final DocumentAnalysisService analysis = new DocumentAnalysisService(documents, assignments,
                new AiTaskExtractionService(ai, properties, new ObjectMapper()), mock(PlatformTransactionManager.class));
        private final DocumentProcessingService realProcessing = new DocumentProcessingService(documents,
                classificationRows, realClassifier, analysis, audit, mock(PlatformTransactionManager.class),
                mock(DocumentOcrService.class));

        /** documents table, in memory: id -> {status, category}. */
        private final Map<Long, AiStatus> status = new HashMap<>();
        private final Map<Long, DocumentCategory> category = new HashMap<>();
        private final Map<Long, String> text = Map.of(
                SYLLABUS_ID, "Силлабус курса INF345 «Алгоритмы». Оценивание: лабораторные 40%, экзамен 60%. "
                        + "Лабораторная 1 — до 2026-11-01, Лабораторная 2 — до 2026-11-15, Проект — до 2026-12-10.",
                PRACTICE_ID, "Практическая работа 4: реализовать сортировку слиянием. Срок сдачи: 2026-11-20 23:59.");
        private final List<DocumentClassification> storedRows = new ArrayList<>();

        @BeforeEach
        void fakeDatabaseAndAi() {
            for (long id : List.of(SYLLABUS_ID, PRACTICE_ID)) {
                status.put(id, AiStatus.PENDING);
                category.put(id, DocumentCategory.OTHER);
            }
            when(documents.changeAiStatus(anyLong(), any(), any())).thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                if (status.get(id) != invocation.getArgument(1)) return 0;
                status.put(id, invocation.getArgument(2));
                return 1;
            });
            when(documents.findClassificationSource(anyLong())).thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                return Optional.of(new ClassificationSource(id, id == SYLLABUS_ID ? "syllabus_INF345.pdf" : "practice4.docx",
                        text.get(id), TEACHER_EMAIL));
            });
            when(documents.updateClassification(anyLong(), any(), any(), any())).thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                category.put(id, invocation.getArgument(1));
                status.put(id, invocation.getArgument(3));
                return 1;
            });
            when(documents.findAnalysisSource(anyLong())).thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                return Optional.of(new AnalysisSource(id, category.get(id), text.get(id), teacher.getId(), COURSE_ID));
            });
            when(documents.lockById(anyLong())).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
            when(documents.getReferenceById(anyLong()))
                    .thenAnswer(invocation -> Document.builder().id(invocation.getArgument(0)).build());
            when(classificationRows.findByDocumentId(anyLong())).thenReturn(Optional.empty());
            when(classificationRows.save(any(DocumentClassification.class))).thenAnswer(invocation -> {
                storedRows.add(invocation.getArgument(0));
                return invocation.getArgument(0);
            });

            // Classification (JSON mode), told apart by the file name in the prompt.
            when(ai.chatJson(anyString(), argThat(message -> message != null && message.contains("syllabus_INF345.pdf"))))
                    .thenReturn(Optional.of("{\"category\": \"SYLLABUS\", \"confidence\": 0.96}"));
            when(ai.chatJson(anyString(), argThat(message -> message != null && message.contains("practice4.docx"))))
                    .thenReturn(Optional.of("{\"category\": \"ASSIGNMENT\", \"confidence\": 0.93}"));
            // Deadline extraction: 3 items in the syllabus, 1 in the practice - 4 in total.
            when(ai.chat(anyString(), argThat(message -> message != null && message.contains("Силлабус курса INF345")))).thenReturn(Optional.of("""
                    {"summary": "Силлабус курса Алгоритмы", "topics": ["Сортировки"], "tasks": [
                      {"title": "Лабораторная 1", "deadline": "2026-11-01T23:59:00"},
                      {"title": "Лабораторная 2", "deadline": "2026-11-15T23:59:00"},
                      {"title": "Проект", "deadline": "2026-12-10T23:59:00"}]}"""));
            when(ai.chat(anyString(), argThat(message -> message != null && message.contains("Практическая работа 4")))).thenReturn(Optional.of("""
                    {"summary": "Сортировка слиянием", "topics": ["Merge sort"], "tasks": [
                      {"title": "Практическая работа 4", "description": "Сортировка слиянием",
                       "deadline": "2026-11-20T23:59:00"}]}"""));
            when(assignments.replaceFromDocument(anyLong(), anyLong(), any(), anyBoolean()))
                    .thenAnswer(invocation -> ((List<?>) invocation.getArgument(2)).size());
        }

        @Test
        @DisplayName("SYLLABUS 0.96 and ASSIGNMENT 0.93: categories set, 2 confidence rows, 4 deadlines; a rerun changes nothing")
        void classifiesBothAndExtractsFourDeadlines() {
            realProcessing.process(SYLLABUS_ID);
            realProcessing.process(PRACTICE_ID);

            assertThat(status).containsEntry(SYLLABUS_ID, AiStatus.DONE).containsEntry(PRACTICE_ID, AiStatus.DONE);
            verify(documents).updateClassification(SYLLABUS_ID, DocumentCategory.SYLLABUS, DocumentType.SYLLABUS, AiStatus.DONE);
            verify(documents).updateClassification(PRACTICE_ID, DocumentCategory.ASSIGNMENT, DocumentType.ASSIGNMENT,
                    AiStatus.DONE);

            assertThat(storedRows).hasSize(2);
            assertThat(storedRows).extracting(DocumentClassification::getCategoryName, DocumentClassification::getConfidenceScore)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("SYLLABUS", 0.96),
                            org.assertj.core.groups.Tuple.tuple("ASSIGNMENT", 0.93));

            ArgumentCaptor<List<TaskExtractDto>> syllabusItems = listCaptor();
            ArgumentCaptor<List<TaskExtractDto>> practiceItems = listCaptor();
            verify(assignments).replaceFromDocument(eq(SYLLABUS_ID), eq(COURSE_ID), syllabusItems.capture(), eq(true));
            verify(assignments).replaceFromDocument(eq(PRACTICE_ID), eq(COURSE_ID), practiceItems.capture(), eq(true));
            assertThat(syllabusItems.getValue()).extracting(TaskExtractDto::title)
                    .containsExactly("Лабораторная 1", "Лабораторная 2", "Проект");
            assertThat(practiceItems.getValue()).singleElement().satisfies(item -> {
                assertThat(item.title()).isEqualTo("Практическая работа 4");
                assertThat(item.deadline()).isEqualTo(LocalDateTime.of(2026, 11, 20, 23, 59));
            });
            assertThat(syllabusItems.getValue().size() + practiceItems.getValue().size()).isEqualTo(4);

            // A duplicate job (e.g. recovery after a restart) finds the documents no longer PENDING.
            realProcessing.process(SYLLABUS_ID);
            realProcessing.process(PRACTICE_ID);

            verify(ai, times(2)).chatJson(anyString(), anyString());
            verify(ai, times(2)).chat(anyString(), anyString());
            verify(assignments, times(2)).replaceFromDocument(anyLong(), anyLong(), any(), anyBoolean());
            assertThat(storedRows).hasSize(2);
            assertThat(status).containsEntry(SYLLABUS_ID, AiStatus.DONE).containsEntry(PRACTICE_ID, AiStatus.DONE);
        }
    }

    @Nested
    @DisplayName("QA-UPL-06: ZIP import bound to the course")
    class QaUpl06 {

        @Test
        @DisplayName("15-file ZIP: 15 documents in INF345 linked to one BatchImport 15/15, 15 jobs queued")
        void fifteenFileArchive() throws IOException {
            List<String> names = new ArrayList<>();
            for (int i = 1; i <= 15; i++) {
                names.add("week" + i + "/lecture" + i + ".txt");
            }

            BatchUploadResponse response = documentService.uploadBatch(zip(names), null, null, COURSE_ID, TEACHER_EMAIL);

            BatchImport batchImport = lastSavedImport();
            assertThat(batchImport.getTotalFiles()).isEqualTo(15);
            assertThat(batchImport.getProcessedFiles()).isEqualTo(15);
            assertThat(batchImport.getCourse()).isSameAs(inf345);
            assertThat(batchImport.getFaculty()).isSameAs(teacher);
            assertThat(response.batchImportId()).isEqualTo(77L);
            assertThat(savedDocuments).hasSize(15).allSatisfy(document -> {
                assertThat(document.getCourse()).isSameAs(inf345);
                assertThat(document.getBatchImport()).isSameAs(batchImport);
                assertThat(document.getAiStatus()).isEqualTo(AiStatus.PENDING);
            });
            verify(processing, times(15)).processAsync(anyLong());
            verify(ai, never()).chatJson(anyString(), anyString());
        }

        @Test
        @DisplayName("\"../../evil.txt\" is skipped as unsafe and nothing is written for it")
        void zipSlipEntryIsSkipped() throws IOException {
            BatchUploadResponse response = documentService.uploadBatch(
                    zip(List.of("lecture.txt", "../../evil.txt")), null, null, COURSE_ID, TEACHER_EMAIL);

            assertThat(response.skipped()).singleElement().satisfies(skipped -> {
                assertThat(skipped.fileName()).isEqualTo("../../evil.txt");
                assertThat(skipped.reason()).isEqualTo("Небезопасный путь в архиве");
            });
            assertThat(savedDocuments).extracting(Document::getOriginalFileName).containsExactly("lecture.txt");
            verify(storage, times(1)).store(any(byte[].class));
            BatchImport batchImport = lastSavedImport();
            assertThat(batchImport.getTotalFiles()).isEqualTo(2);
            assertThat(batchImport.getProcessedFiles()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Security rules")
    class Security {

        @Test
        @DisplayName("rule A: EXAM_ANSWERS is stored TEACHER_ONLY whatever the uploader chose")
        void examAnswersAreTeacherOnly() throws IOException {
            documentService.upload(file("exam_key.pdf", pdf()), DocumentCategory.EXAM_ANSWERS,
                    DocumentVisibility.COURSE_STUDENTS, COURSE_ID, TEACHER_EMAIL);

            assertThat(savedDocuments).singleElement().satisfies(document -> {
                assertThat(document.getVisibility()).isEqualTo(DocumentVisibility.TEACHER_ONLY);
                assertThat(document.getType()).isEqualTo(DocumentType.EXAM_ANSWERS);
            });
            verify(audit).record(eq(TEACHER_EMAIL), eq(AuditService.DOCUMENT_UPLOADED),
                    argThat(details -> details.endsWith("visibility forced to TEACHER_ONLY")));
        }

        @Test
        @DisplayName("rule C: an enrolled student is denied a PENDING document; its teacher is not")
        void pendingDocumentIsHiddenFromStudents() {
            Document pending = Document.builder().id(900L).course(inf345).owner(teacher)
                    .visibility(DocumentVisibility.COURSE_STUDENTS).category(DocumentCategory.OTHER)
                    .aiStatus(AiStatus.PENDING).build();
            when(documents.findWithOwnerById(900L)).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> access.authorizeDocument(student, 900L, Permission.VIEW_DOCUMENT))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThat(access.authorizeDocument(teacher, 900L, Permission.VIEW_DOCUMENT)).isSameAs(pending);
        }

        @Test
        @DisplayName("rule B: analysis of EXAM_ANSWERS is rejected with 400")
        void examAnswersAreNeverAnalyzed() {
            Document exam = Document.builder().id(901L).course(inf345).owner(teacher)
                    .visibility(DocumentVisibility.TEACHER_ONLY).category(DocumentCategory.EXAM_ANSWERS)
                    .aiStatus(AiStatus.DONE).textSnippet("Ответы").build();
            when(documents.findWithOwnerById(901L)).thenReturn(Optional.of(exam));

            assertThatThrownBy(() -> documentService.requestAnalysis(901L, TEACHER_EMAIL))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getMessage()).isEqualTo("Ответы к экзамену не анализируются на задания");
                    });
        }
    }

    private BatchImport lastSavedImport() {
        ArgumentCaptor<BatchImport> saved = ArgumentCaptor.forClass(BatchImport.class);
        verify(imports, times(2)).save(saved.capture());
        return saved.getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<TaskExtractDto>> listCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, "application/octet-stream", content);
    }

    private static byte[] pdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docx() throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Лабораторная работа 1");
            document.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static MockMultipartFile zip(List<String> entryNames) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (String name : entryNames) {
                out.putNextEntry(new ZipEntry(name));
                out.write(("Содержимое " + name).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return new MockMultipartFile("file", "INF345_lectures.zip", "application/zip", bytes.toByteArray());
    }
}
