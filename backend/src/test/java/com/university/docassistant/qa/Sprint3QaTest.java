package com.university.docassistant.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.university.docassistant.config.AiProperties;
import com.university.docassistant.config.DocumentSearchSchema;
import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.AssignmentDtos.UpdateAssignmentRequest;
import com.university.docassistant.dto.DocumentResponse;
import com.university.docassistant.dto.DocumentTextResponse;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.OcrStatus;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.Task;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.AssignmentRepository.MatchInfo;
import com.university.docassistant.repository.BatchImportRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentChunkRepository;
import com.university.docassistant.repository.DocumentChunkRepository.ChunkHit;
import com.university.docassistant.repository.DocumentClassificationRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentRepository.AnalysisSource;
import com.university.docassistant.repository.DocumentRepository.ClassificationSource;
import com.university.docassistant.repository.DocumentRepository.OcrSource;
import com.university.docassistant.repository.DocumentSummary;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.service.AiClientService;
import com.university.docassistant.service.AiTaskExtractionService;
import com.university.docassistant.service.AssignmentEvents;
import com.university.docassistant.service.AssignmentService;
import com.university.docassistant.service.AuditService;
import com.university.docassistant.service.DocumentAnalysisService;
import com.university.docassistant.service.DocumentChunkService;
import com.university.docassistant.service.DocumentClassificationService;
import com.university.docassistant.service.DocumentOcrService;
import com.university.docassistant.service.DocumentParserService;
import com.university.docassistant.service.DocumentParserService.ExtractedText;
import com.university.docassistant.service.DocumentPrivacyService;
import com.university.docassistant.service.DocumentProcessingService;
import com.university.docassistant.service.DocumentQnaService;
import com.university.docassistant.service.DocumentService;
import com.university.docassistant.service.OcrEngine;
import com.university.docassistant.service.OcrEngine.OcrException;
import com.university.docassistant.service.OcrEngine.Recognized;
import com.university.docassistant.service.storage.StorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 3 acceptance cases (QA-AI-008, QA-SEC-009, QA-OCR-007) end to end, from the upload to what each role sees.
 * The AI (AiClientService) and Tesseract (OcrEngine) are Mockito mocks and every request to the AI is recorded; the
 * repositories are in-memory stand-ins for the tables the cases touch (documents, assignments, tasks, chunks). All
 * services in between are the real code: access control, masking, OCR, classification, deadline extraction,
 * assignments, Q&A. The background executor is a queue the test drains itself, so "in the background" is checked
 * literally: nothing of the AI or OCR runs before the worker does. The SQL of fanOut, search and chunks needs
 * PostgreSQL (AssignmentLifecyclePostgresTest, opt-in); here its query texts are checked.
 */
class Sprint3QaTest {

    private static final String TEACHER_EMAIL = "o.kim@uni.kz";
    private static final String STUDENT_EMAIL = "student@uni.kz";
    private static final String CLASSMATE_EMAIL = "classmate@uni.kz";
    private static final String DEAN_EMAIL = "dean@uni.kz";
    private static final long COURSE_ID = 345L;

    private static final String IIN = "990101300013";
    private static final String PHONE = "701 123 45 67";
    private static final String PRIVATE_EMAIL = "ivanov@mail.ru";

    private static final String SYLLABUS = """
            Силлабус курса INF345 «Алгоритмы». Преподаватель: Ким О., o.kim@uni.kz.
            Староста группы: Иванов А., ИИН 990101300013, тел. +7 701 123 45 67, ivanov@mail.ru.
            Оценивание: лабораторные работы 40%, экзамен 60%.
            Лабораторная работа 1 — до 2026-11-01 23:59. Лабораторная работа 2 — до 2026-11-15 23:59.""";
    private static final String SYLLABUS_TASKS = """
            {"summary": "Силлабус курса Алгоритмы", "topics": ["Сортировки"], "tasks": [
              {"title": "Лабораторная работа 1", "description": "Сортировки", "deadline": "2026-11-01T23:59:00"},
              {"title": "Лабораторная работа 2", "description": "Графы", "deadline": "2026-11-15T23:59:00"}]}""";
    private static final String QA_REPLY = "Лабораторная работа 1 сдаётся до 1 ноября [syllabus.txt, фрагмент 1]";

    private static final LocalDateTime LAB1_DEADLINE = LocalDateTime.of(2026, 11, 1, 23, 59);
    private static final LocalDateTime LAB1_NEW_DEADLINE = LocalDateTime.of(2026, 11, 8, 23, 59);

    private static final AiProperties AI_PROPERTIES = new AiProperties(true, "test-key", "openai/gpt-oss-120b",
            "http://ai.test", Duration.ofSeconds(1), Duration.ofSeconds(1), 60_000, 1, 1, 1, 1);

    private final User teacher = User.builder().id(2L).email(TEACHER_EMAIL).fullName("Ким О.").role(Role.ROLE_TEACHER)
            .build();
    private final User student = User.builder().id(1L).email(STUDENT_EMAIL).fullName("Студент").role(Role.ROLE_STUDENT)
            .build();
    private final User classmate = User.builder().id(3L).email(CLASSMATE_EMAIL).fullName("Однокурсник")
            .role(Role.ROLE_STUDENT).build();
    private final User dean = User.builder().id(4L).email(DEAN_EMAIL).fullName("Деканат").role(Role.ROLE_ADMIN).build();
    private final Course inf345 = Course.builder().id(COURSE_ID).code("INF345").title("Algorithms").teacher(teacher)
            .build();
    private final List<User> enrolled = List.of(student, classmate);

    // Repositories and infrastructure: mocks backed by the in-memory tables below.
    private final UserRepository users = mock(UserRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final AssignmentRepository assignmentRows = mock(AssignmentRepository.class);
    private final TaskRepository taskRows = mock(TaskRepository.class);
    private final DocumentClassificationRepository classificationRows = mock(DocumentClassificationRepository.class);
    private final DocumentChunkRepository chunkRows = mock(DocumentChunkRepository.class);
    private final DocumentChunkService chunker = mock(DocumentChunkService.class);
    private final StorageService storage = mock(StorageService.class);
    private final DocumentParserService parser = mock(DocumentParserService.class);
    private final AuditService audit = mock(AuditService.class);
    private final AssignmentEvents events = mock(AssignmentEvents.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    /** The two external systems. */
    private final AiClientService ai = mock(AiClientService.class);
    private final OcrEngine tesseract = mock(OcrEngine.class);
    /** The AI executor as DocumentService sees it: jobs are only queued, the test runs them as the worker. */
    private final DocumentProcessingService executor = mock(DocumentProcessingService.class);

    // The real services.
    private final DocumentPrivacyService privacy = new DocumentPrivacyService(new PrivacyProperties(true, List.of()));
    private final AccessControlService access = new AccessControlService(users, courses, documents, enrollments, audit);
    private final AssignmentService assignments = new AssignmentService(assignmentRows, taskRows, courses, documents,
            access, audit, events, false);
    private final DocumentOcrService ocr = new DocumentOcrService(tesseract, documents, storage, privacy, chunker,
            transactions);
    private final DocumentClassificationService classifier = new DocumentClassificationService(ai, classificationRows,
            documents, new ObjectMapper(), privacy);
    private final DocumentAnalysisService analysis = new DocumentAnalysisService(documents, assignments,
            new AiTaskExtractionService(ai, AI_PROPERTIES, new ObjectMapper()), transactions);
    private final DocumentProcessingService worker = new DocumentProcessingService(documents, classificationRows,
            classifier, analysis, audit, transactions, ocr);
    private final DocumentService documentService = new DocumentService(documents, taskRows, assignmentRows, access,
            storage, parser, analysis, ai, chunker, audit, classifier, executor, mock(BatchImportRepository.class),
            assignments, privacy);
    private final DocumentQnaService qna = new DocumentQnaService(documentService, chunkRows, access, ai, AI_PROPERTIES,
            privacy);

    // In-memory tables.
    private final TreeMap<Long, Document> documentTable = new TreeMap<>();
    private final Map<Long, Assignment> assignmentTable = new TreeMap<>();
    private final List<Task> taskTable = new ArrayList<>();
    /** document id -> the text DocumentChunkService cut into Q&A chunks. */
    private final Map<Long, String> chunkTable = new HashMap<>();
    private final List<Long> queuedJobs = new ArrayList<>();
    /** Every request sent to the AI client: system prompt and user message. */
    private final List<String> aiRequests = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1000);

    private String classificationReply = "{\"category\": \"SYLLABUS\", \"confidence\": 0.95}";
    private String analysisReply = SYLLABUS_TASKS;

    @BeforeEach
    void setUp() throws Exception {
        for (User user : List.of(teacher, student, classmate, dean)) {
            when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        }
        when(courses.findWithTeacherById(COURSE_ID)).thenReturn(Optional.of(inf345));
        when(courses.getReferenceById(COURSE_ID)).thenReturn(inf345);
        when(courses.findIdsByTeacherId(teacher.getId())).thenReturn(List.of(COURSE_ID));
        when(courses.findAllIds()).thenReturn(List.of(COURSE_ID));
        for (User user : enrolled) {
            when(enrollments.existsByStudentIdAndCourseId(user.getId(), COURSE_ID)).thenReturn(true);
            when(enrollments.findCourseIdsByStudentId(user.getId())).thenReturn(List.of(COURSE_ID));
        }
        when(storage.store(any(byte[].class))).thenAnswer(invocation -> "stored-" + ids.incrementAndGet());
        when(storage.load(anyString())).thenReturn(new ByteArrayResource(new byte[]{1, 2, 3}));
        when(chunker.index(anyLong(), any())).thenAnswer(invocation -> {
            chunkTable.put(invocation.getArgument(0), invocation.getArgument(1));
            return 1;
        });

        // The executor only queues; the worker below is the real pipeline.
        doAnswer(invocation -> queuedJobs.add(invocation.getArgument(0))).when(executor).processAsync(anyLong());
        doAnswer(invocation -> queuedJobs.add(invocation.getArgument(0))).when(executor).ocrAsync(anyLong());
        when(executor.needsOcr(any(), any())).thenAnswer(invocation ->
                ocr.needsOcr(invocation.getArgument(0), invocation.getArgument(1)));
        when(executor.ocrAvailable()).thenAnswer(invocation -> ocr.isAvailable());
        when(tesseract.isAvailable()).thenReturn(true);

        when(ai.isConfigured()).thenReturn(true);
        when(ai.chatJson(anyString(), anyString())).thenAnswer(invocation -> {
            aiRequests.add(invocation.getArgument(0) + "\n" + invocation.getArgument(1));
            return Optional.of(classificationReply);
        });
        when(ai.chat(anyString(), anyString())).thenAnswer(invocation -> {
            String message = invocation.getArgument(1);
            aiRequests.add(invocation.getArgument(0) + "\n" + message);
            return Optional.of(message.startsWith("Вопрос:") ? QA_REPLY : analysisReply);
        });

        fakeDocumentsTable();
        fakeAssignmentsAndTasksTables();
        fakeChunks();
    }

    @Nested
    @DisplayName("QA-AI-008: deadlines found by the AI are drafts the teacher reviews")
    class QaAi008 {

        @Test
        @DisplayName("syllabus analysis creates drafts only: no student gets a task, students cannot list drafts")
        void analysisCreatesDraftsOnly() throws IOException {
            long syllabus = uploadAndProcess("syllabus.txt", SYLLABUS);

            assertThat(documentTable.get(syllabus).getCategory()).isEqualTo(DocumentCategory.SYLLABUS);
            assertThat(assignmentTable.values()).hasSize(2).allSatisfy(assignment -> {
                assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.DRAFT);
                assertThat(assignment.getSourceDocument().getId()).isEqualTo(syllabus);
            });
            assertThat(assignmentTable.values()).extracting(Assignment::getTitle, Assignment::getDeadline)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("Лабораторная работа 1", LAB1_DEADLINE),
                            org.assertj.core.groups.Tuple.tuple("Лабораторная работа 2",
                                    LocalDateTime.of(2026, 11, 15, 23, 59)));
            assertThat(taskTable).isEmpty();
            assertForbidden(() -> assignments.drafts(COURSE_ID, STUDENT_EMAIL, Pageable.unpaged()), null);
            assertForbidden(() -> assignments.publishAll(COURSE_ID, draftIds(), STUDENT_EMAIL), null);
            assertThat(taskTable).isEmpty();
        }

        @Test
        @DisplayName("publishing hands the tasks out to every enrolled student, once")
        void publishingHandsOutTasks() throws IOException {
            uploadAndProcess("syllabus.txt", SYLLABUS);

            assignments.publishAll(COURSE_ID, draftIds(), TEACHER_EMAIL);

            assertThat(assignmentTable.values()).extracting(Assignment::getStatus).containsOnly(AssignmentStatus.ACTIVE);
            assertThat(taskTable).hasSize(4).allSatisfy(task -> assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING));
            assertThat(tasksOf(student)).extracting(Task::getTitle)
                    .containsExactlyInAnyOrder("Лабораторная работа 1", "Лабораторная работа 2");
            assertThat(tasksOf(classmate)).hasSize(2);
            verify(audit).record(eq(TEACHER_EMAIL), eq(AuditService.ASSIGNMENT_PUBLISHED), contains("4 student task(s)"));

            // A second publish is refused and hands out nothing twice.
            assertThatThrownBy(() -> assignments.publishAll(COURSE_ID, List.copyOf(assignmentTable.keySet()),
                    TEACHER_EMAIL)).isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.getCode()).isEqualTo(ErrorCodes.ASSIGNMENT_NOT_DRAFT));
            assertThat(taskTable).hasSize(4);
        }

        @Test
        @DisplayName("editing a published deadline reaches the tasks and keeps student progress, also after re-analysis")
        void editingAPublishedDeadlineKeepsStudentProgress() throws IOException {
            long syllabus = uploadAndProcess("syllabus.txt", SYLLABUS);
            assignments.publishAll(COURSE_ID, draftIds(), TEACHER_EMAIL);
            Assignment lab1 = assignmentTitled("Лабораторная работа 1");
            Task studentsLab1 = taskOf(student, lab1);
            studentsLab1.setStatus(TaskStatus.IN_PROGRESS); // The student started working on it.

            assignments.update(lab1.getId(), new UpdateAssignmentRequest("Лабораторная работа 1", "Сортировки",
                    LAB1_NEW_DEADLINE), TEACHER_EMAIL);

            assertThat(taskTable).hasSize(4);
            assertThat(studentsLab1.getDeadline()).isEqualTo(LAB1_NEW_DEADLINE);
            assertThat(studentsLab1.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(taskOf(classmate, lab1).getDeadline()).isEqualTo(LAB1_NEW_DEADLINE);
            assertThat(taskOf(classmate, lab1).getStatus()).isEqualTo(TaskStatus.PENDING);
            verify(audit).record(eq(TEACHER_EMAIL), eq(AuditService.ASSIGNMENT_DEADLINE_CHANGED),
                    contains("2026-11-01T23:59 -> 2026-11-08T23:59"));
            verify(events).deadlineChanged(lab1, LAB1_DEADLINE, LAB1_NEW_DEADLINE);

            // The AI reads the syllabus again and still finds the old deadline: the teacher's edit wins.
            analysis.analyzeNow(syllabus, false);

            assertThat(assignmentTable).hasSize(2);
            assertThat(lab1.getDeadline()).isEqualTo(LAB1_NEW_DEADLINE);
            assertThat(lab1.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            assertThat(taskTable).hasSize(4);
            assertThat(studentsLab1.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(studentsLab1.getDeadline()).isEqualTo(LAB1_NEW_DEADLINE);
        }

        @Test
        @DisplayName("a draft that looks like a published assignment is marked as a duplicate, never merged")
        void duplicateDraftIsMarked() throws IOException {
            uploadAndProcess("syllabus.txt", SYLLABUS);
            assignments.publishAll(COURSE_ID, draftIds(), TEACHER_EMAIL);
            Assignment lab1 = assignmentTitled("Лабораторная работа 1");

            // A lab sheet for the same lab, uploaded later, with the title written differently.
            analysisReply = """
                    {"summary": "Лабораторная 1", "topics": [], "tasks": [
                      {"title": "Лаб. №1: сортировки", "deadline": "2026-11-01T18:00:00"}]}""";
            classificationReply = "{\"category\": \"ASSIGNMENT\", \"confidence\": 0.91}";
            long labSheet = uploadAndProcess("lab1.txt", "Лабораторная работа №1. Реализуйте сортировку слиянием и "
                    + "быструю сортировку, сравните время работы. Срок сдачи: 2026-11-01 18:00.");

            Assignment draft = assignmentTable.values().stream()
                    .filter(assignment -> assignment.getSourceDocument().getId() == labSheet).findFirst().orElseThrow();
            assertThat(draft.getStatus()).isEqualTo(AssignmentStatus.DRAFT);
            assertThat(draft.getDuplicateOf()).isSameAs(lab1);
            assertThat(lab1.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            assertThat(assignmentTable).hasSize(3);
            assertThat(taskTable).hasSize(4); // The draft is not handed out.

            // "Это другое задание" clears the mark.
            assignments.notDuplicate(draft.getId(), TEACHER_EMAIL);
            assertThat(draft.getDuplicateOf()).isNull();
        }
    }

    @Nested
    @DisplayName("QA-SEC-009: personal data is masked for students and never sent to the AI")
    class QaSec009 {

        private long syllabus;

        @BeforeEach
        void uploadSyllabusWithPersonalData() throws IOException {
            syllabus = uploadAndProcess("syllabus.txt", SYLLABUS);
        }

        @Test
        @DisplayName("a student receives the masked text; the teacher the original")
        void studentReceivesMaskedText() {
            DocumentTextResponse forStudent = documentService.text(syllabus, STUDENT_EMAIL);
            DocumentTextResponse forTeacher = documentService.text(syllabus, TEACHER_EMAIL);

            assertThat(forStudent.masked()).isTrue();
            assertNoPersonalData(forStudent.text());
            assertThat(forStudent.text()).contains("[ИИН скрыт]", "[телефон скрыт]", "[e-mail скрыт]",
                    TEACHER_EMAIL, "2026-11-01 23:59");
            assertThat(forTeacher.masked()).isFalse();
            assertThat(forTeacher.text()).isEqualTo(SYLLABUS);
            // The document as listed to the student: snippet and metadata only, nothing personal.
            assertNoPersonalData(json(documentService.get(syllabus, STUDENT_EMAIL)));
        }

        @Test
        @DisplayName("search runs over the masked text, and its results show a student nothing personal")
        void noIinInSearch() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            new DocumentSearchSchema(jdbc).run(null);
            ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
            verify(jdbc, org.mockito.Mockito.atLeastOnce()).execute(ddl.capture());
            assertThat(ddl.getAllValues()).filteredOn(sql -> sql.contains("ADD COLUMN IF NOT EXISTS text_tsv")
                            && sql.contains("documents"))
                    .singleElement().satisfies(sql -> assertThat(sql).contains("masked_text").doesNotContain("extracted_text"));
            assertThat(searchQueries()).isNotEmpty().allSatisfy(sql ->
                    assertThat(sql).contains("text_tsv").doesNotContain("extracted_text"));
            // So the IIN is not in what the index is built from...
            assertNoPersonalData(documentTable.get(syllabus).getMaskedText());
            assertNoPersonalData(chunkTable.get(syllabus));

            // ...and a hit ("Алгоритмы") carries only the masked snippet.
            when(documents.searchVisibleIds(eq("Алгоритмы"), anyBoolean(), anyCollection(), anyBoolean(), any()))
                    .thenReturn(new PageImpl<>(List.of(syllabus), PageRequest.of(0, 20), 1));
            PageResponse<DocumentResponse> hits = documentService.search("Алгоритмы", STUDENT_EMAIL, PageRequest.of(0, 20));

            assertThat(hits.content()).singleElement().satisfies(hit -> assertThat(hit.id()).isEqualTo(syllabus));
            assertNoPersonalData(json(hits));
        }

        @Test
        @DisplayName("Q&A over the document and over the course sends the AI no IIN, phone or private e-mail")
        void noIinInQaContext() {
            aiRequests.clear();

            qna.askDocument(syllabus, "Когда сдавать лабораторную работу 1?", STUDENT_EMAIL);
            qna.askCourse(COURSE_ID, "Какой ИИН у старосты группы?", STUDENT_EMAIL);

            assertThat(aiRequests).hasSize(2).allSatisfy(request -> {
                assertNoPersonalData(request);
                assertThat(request).contains("[КОНТЕКСТ ДОКУМЕНТОВ]", "[ИИН скрыт]", "Никогда не пытайся восстановить");
            });
        }

        @Test
        @DisplayName("a student gets 403 on the original file, and the refusal is audited")
        void studentGets403OnTheOriginal() {
            assertForbidden(() -> documentService.view(syllabus, STUDENT_EMAIL), ErrorCodes.PII_ORIGINAL_RESTRICTED);
            assertForbidden(() -> documentService.download(syllabus, STUDENT_EMAIL), ErrorCodes.PII_ORIGINAL_RESTRICTED);

            verify(storage, never()).load(documentTable.get(syllabus).getStoredFileName());
            verify(audit).record(eq(STUDENT_EMAIL), eq(AuditService.ACCESS_DENIED), contains("(view): contains personal data"));
            verify(audit).record(eq(STUDENT_EMAIL), eq(AuditService.ACCESS_DENIED), contains("(download): contains personal data"));
            verify(audit, never()).record(eq(STUDENT_EMAIL), eq(AuditService.PII_ORIGINAL_VIEWED), anyString());
        }

        @Test
        @DisplayName("every opening of the original by the teacher or the dean's office is audited")
        void teacherAccessIsAudited() {
            documentService.view(syllabus, TEACHER_EMAIL);
            documentService.download(syllabus, DEAN_EMAIL);

            verify(audit).record(eq(TEACHER_EMAIL), eq(AuditService.PII_ORIGINAL_VIEWED),
                    contains("(view, 3 personal data item(s): EMAIL,IIN,PHONE)"));
            verify(audit).record(eq(DEAN_EMAIL), eq(AuditService.PII_ORIGINAL_VIEWED), contains("(download, 3"));
        }

        @Test
        @DisplayName("no request of the whole flow - classification, deadline extraction, Q&A - carries personal data")
        void noPersonalDataInAnyAiRequest() {
            qna.askDocument(syllabus, "О чём этот документ?", STUDENT_EMAIL);
            qna.askCourse(COURSE_ID, "Как связаться со старостой?", TEACHER_EMAIL);

            // Classification (JSON mode), deadline extraction, two questions.
            verify(ai).chatJson(anyString(), anyString());
            verify(ai, org.mockito.Mockito.times(3)).chat(anyString(), anyString());
            assertThat(aiRequests).hasSize(4).allSatisfy(Sprint3QaTest::assertNoPersonalData);
            assertThat(aiRequests.get(0)).contains("Имя файла: syllabus.txt", "[ИИН скрыт]");
            assertThat(aiRequests.get(1)).contains("Текст документа:", "[телефон скрыт]", "2026-11-01 23:59");
        }
    }

    @Nested
    @DisplayName("QA-OCR-007: a scan gets its text by OCR in the background")
    class QaOcr007 {

        private static final String SCANNED = """
                Силлабус курса INF345 «Алгоритмы» (скан). Староста: Иванов А., ИИН 990101300013.
                Лабораторная работа 1 — до 2026-11-01 23:59. Лабораторная работа 2 — до 2026-11-15 23:59.""";

        private long scan;

        @BeforeEach
        void uploadScan() throws Exception {
            when(parser.extractText(any(InputStream.class), anyString(), anyString())).thenReturn(ExtractedText.NONE);
            documentService.upload(new MockMultipartFile("file", "scan.pdf", "application/pdf", emptyPdf()), null, null,
                    COURSE_ID, TEACHER_EMAIL);
            scan = lastDocumentId();
        }

        @Test
        @DisplayName("upload only queues it; the worker runs OCR, then masking, classification and deadline drafts")
        void scanGoesThroughOcrThenMaskingClassificationAndDrafts() throws Exception {
            Document document = documentTable.get(scan);
            assertThat(document.getOcrStatus()).isEqualTo(OcrStatus.PENDING);
            assertThat(document.getAiStatus()).isEqualTo(AiStatus.PENDING);
            assertThat(queuedJobs).containsExactly(scan);
            verify(tesseract, never()).recognize(any(), any(), any());
            assertThat(aiRequests).isEmpty();
            // Rule C: students do not see it while it is being processed.
            assertForbidden(() -> documentService.get(scan, STUDENT_EMAIL), null);
            assertThat(documentService.get(scan, TEACHER_EMAIL).ocrStatus()).isEqualTo(OcrStatus.PENDING);

            when(tesseract.recognize(any(), eq("scan.pdf"), eq("application/pdf"))).thenReturn(new Recognized(SCANNED, false));
            runQueuedJobs();

            InOrder order = inOrder(tesseract, chunker, ai);
            order.verify(tesseract).recognize(any(), eq("scan.pdf"), eq("application/pdf"));
            order.verify(chunker).index(eq(scan), argThatHasNoPersonalData());
            order.verify(ai).chatJson(anyString(), anyString());
            order.verify(ai).chat(anyString(), anyString());

            assertThat(document.getOcrStatus()).isEqualTo(OcrStatus.DONE);
            assertThat(document.isTextFromOcr()).isTrue();
            assertThat(document.getExtractedText()).isEqualTo(SCANNED);
            assertNoPersonalData(document.getMaskedText());
            assertNoPersonalData(document.getTextSnippet());
            assertThat(document.getPiiCount()).isEqualTo(1);
            assertThat(document.getAiStatus()).isEqualTo(AiStatus.DONE);
            assertThat(document.getCategory()).isEqualTo(DocumentCategory.SYLLABUS);
            assertThat(assignmentTable.values()).hasSize(2)
                    .extracting(Assignment::getStatus).containsOnly(AssignmentStatus.DRAFT);
            assertThat(taskTable).isEmpty();
            assertThat(aiRequests).hasSize(2).allSatisfy(Sprint3QaTest::assertNoPersonalData);

            // Now the student sees it, marked as recognized, with the masked text.
            DocumentResponse forStudent = documentService.get(scan, STUDENT_EMAIL);
            assertThat(forStudent.textFromOcr()).isTrue();
            assertNoPersonalData(documentService.text(scan, STUDENT_EMAIL).text());
        }

        @Test
        @DisplayName("an OCR failure keeps the document, flagged for the teacher, until the retry succeeds")
        void ocrFailureKeepsTheDocumentFlagged() throws Exception {
            when(tesseract.recognize(any(), anyString(), anyString()))
                    .thenThrow(new OcrException("OCR took longer than 120 s", true, null));
            runQueuedJobs();

            Document document = documentTable.get(scan);
            assertThat(documentTable).containsKey(scan);
            verify(storage, never()).delete(anyString());
            assertThat(document.getOcrStatus()).isEqualTo(OcrStatus.FAILED);
            assertThat(document.getExtractedText()).isNull();
            assertThat(document.isTextFromOcr()).isFalse();
            assertThat(chunkTable).doesNotContainKey(scan);
            assertThat(assignmentTable).isEmpty();
            // Classified by its file name only; no deadline extraction without text.
            verify(ai).chatJson(anyString(), contains("текст не распознан"));
            verify(ai, never()).chat(anyString(), anyString());
            // "Нужна проверка": the teacher sees the failure.
            assertThat(documentService.get(scan, TEACHER_EMAIL).ocrStatus()).isEqualTo(OcrStatus.FAILED);

            // A student cannot retry; the teacher can, and the job is queued again only after the commit.
            TransactionSynchronizationManager.initSynchronization();
            try {
                assertForbidden(() -> documentService.retryOcr(scan, STUDENT_EMAIL), null);
                documentService.retryOcr(scan, TEACHER_EMAIL);
                assertThat(queuedJobs).isEmpty();
                TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
            verify(audit).record(eq(TEACHER_EMAIL), eq(AuditService.OCR_RETRY_REQUESTED), contains("classified again"));
            assertThat(document.getOcrStatus()).isEqualTo(OcrStatus.PENDING);
            assertThat(queuedJobs).containsExactly(scan);

            org.mockito.Mockito.reset(tesseract);
            when(tesseract.isAvailable()).thenReturn(true);
            when(tesseract.recognize(any(), anyString(), anyString())).thenReturn(new Recognized(SCANNED, false));
            runQueuedJobs();

            assertThat(document.getOcrStatus()).isEqualTo(OcrStatus.DONE);
            assertThat(document.getAiStatus()).isEqualTo(AiStatus.DONE);
            assertThat(assignmentTable.values()).hasSize(2)
                    .extracting(Assignment::getStatus).containsOnly(AssignmentStatus.DRAFT);
        }
    }

    // ---------------------------------------------------------------- flow helpers

    /** Upload without a category as the course teacher, then the background job; returns the document id. */
    private long uploadAndProcess(String fileName, String text) throws IOException {
        when(parser.extractText(any(InputStream.class), eq(fileName), anyString()))
                .thenReturn(new ExtractedText(text, false));
        documentService.upload(new MockMultipartFile("file", fileName, "text/plain",
                text.getBytes(StandardCharsets.UTF_8)), null, null, COURSE_ID, TEACHER_EMAIL);
        long id = lastDocumentId();
        assertThat(queuedJobs).containsExactly(id);
        runQueuedJobs();
        return id;
    }

    /** What the AI executor does with the queue: each job runs on its own, after the request has returned. */
    private void runQueuedJobs() {
        List<Long> jobs = List.copyOf(queuedJobs);
        queuedJobs.clear();
        jobs.forEach(worker::process);
    }

    private long lastDocumentId() {
        return documentTable.lastKey();
    }

    private List<Long> draftIds() {
        return assignmentTable.values().stream()
                .filter(assignment -> assignment.getStatus() == AssignmentStatus.DRAFT).map(Assignment::getId).toList();
    }

    private Assignment assignmentTitled(String title) {
        return assignmentTable.values().stream().filter(assignment -> title.equals(assignment.getTitle()))
                .findFirst().orElseThrow();
    }

    private List<Task> tasksOf(User user) {
        return taskTable.stream().filter(task -> task.getUser() == user).toList();
    }

    private Task taskOf(User user, Assignment assignment) {
        return tasksOf(user).stream().filter(task -> task.getAssignment() == assignment).findFirst().orElseThrow();
    }

    private static void assertNoPersonalData(String text) {
        assertThat(text).isNotNull().doesNotContain(IIN).doesNotContain(PHONE).doesNotContain(PRIVATE_EMAIL);
    }

    private static String argThatHasNoPersonalData() {
        return org.mockito.ArgumentMatchers.argThat(text -> text != null && !text.contains(IIN));
    }

    private static void assertForbidden(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            if (code != null) {
                assertThat(ex.getCode()).isEqualTo(code);
            }
        });
    }

    private static String json(Object value) {
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(value);
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    private static List<String> searchQueries() {
        return java.util.Arrays.stream(DocumentRepository.class.getMethods())
                .filter(method -> method.getName().equals("searchVisibleIds"))
                .map(method -> method.getAnnotation(Query.class))
                .flatMap(query -> java.util.stream.Stream.of(query.value(), query.countQuery()))
                .toList();
    }

    private static byte[] emptyPdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- in-memory tables

    private void fakeDocumentsTable() {
        when(documents.save(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            if (document.getId() == null) {
                document.setId(ids.incrementAndGet());
            }
            documentTable.put(document.getId(), document);
            return document;
        });
        when(documents.findWithOwnerById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(documentTable.get(invocation.<Long>getArgument(0))));
        when(documents.getReferenceById(anyLong())).thenAnswer(invocation -> documentTable.get(invocation.<Long>getArgument(0)));
        when(documents.findSummariesByIdIn(anyCollection())).thenAnswer(invocation ->
                invocation.<Collection<Long>>getArgument(0).stream().map(documentTable::get).map(DocumentSummary::of).toList());
        when(documents.changeAiStatus(anyLong(), any(), any())).thenAnswer(invocation -> {
            Document document = documentTable.get(invocation.<Long>getArgument(0));
            if (document == null || document.getAiStatus() != invocation.getArgument(1)) {
                return 0;
            }
            document.setAiStatus(invocation.getArgument(2));
            return 1;
        });
        when(documents.setAiStatus(anyLong(), any())).thenAnswer(invocation -> {
            documentTable.get(invocation.<Long>getArgument(0)).setAiStatus(invocation.getArgument(1));
            return 1;
        });
        when(documents.changeOcrStatus(anyLong(), any(), any())).thenAnswer(invocation -> {
            Document document = documentTable.get(invocation.<Long>getArgument(0));
            if (document == null || document.getOcrStatus() != invocation.getArgument(1)) {
                return 0;
            }
            document.setOcrStatus(invocation.getArgument(2));
            return 1;
        });
        when(documents.findOcrSource(anyLong())).thenAnswer(invocation -> {
            Document document = documentTable.get(invocation.<Long>getArgument(0));
            return Optional.of(new OcrSource(document.getId(), document.getStoredFileName(),
                    document.getOriginalFileName(), document.getContentType(), TEACHER_EMAIL, null));
        });
        when(documents.updateRecognizedText(anyLong(), any(), anyBoolean(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> {
                    Document document = documentTable.get(invocation.<Long>getArgument(0));
                    document.setExtractedText(invocation.getArgument(1));
                    document.setTextTruncated(invocation.getArgument(2));
                    document.setMaskedText(invocation.getArgument(3));
                    document.setTextSnippet(invocation.getArgument(4));
                    document.setPiiCount(invocation.getArgument(5));
                    document.setPiiTypes(invocation.getArgument(6));
                    document.setPiiScan(invocation.getArgument(7));
                    document.setTextFromOcr(true);
                    document.setOcrStatus(OcrStatus.DONE);
                    return 1;
                });
        when(documents.findClassificationSource(anyLong())).thenAnswer(invocation -> {
            Document document = documentTable.get(invocation.<Long>getArgument(0));
            return Optional.of(new ClassificationSource(document.getId(), document.getOriginalFileName(),
                    document.getMaskedText(), document.getOwner().getEmail()));
        });
        when(documents.updateClassification(anyLong(), any(), any(), any())).thenAnswer(invocation -> {
            Document document = documentTable.get(invocation.<Long>getArgument(0));
            document.setCategory(invocation.getArgument(1));
            document.setType(invocation.getArgument(2));
            document.setAiStatus(invocation.getArgument(3));
            return 1;
        });
        when(documents.forceTeacherOnly(anyLong())).thenAnswer(invocation -> {
            Document document = documentTable.get(invocation.<Long>getArgument(0));
            if (document.getVisibility() == DocumentVisibility.TEACHER_ONLY) {
                return 0;
            }
            document.setVisibility(DocumentVisibility.TEACHER_ONLY);
            return 1;
        });
        when(documents.findAnalysisSource(anyLong())).thenAnswer(invocation -> {
            Document document = documentTable.get(invocation.<Long>getArgument(0));
            return Optional.of(new AnalysisSource(document.getId(), document.getCategory(), document.getMaskedText(),
                    document.getOwner().getId(), document.getCourse().getId()));
        });
        when(documents.lockById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(documentTable.containsKey(invocation.<Long>getArgument(0)) ? invocation.getArgument(0) : null));
    }

    /** The assignments table, and the tasks table with the semantics of TaskRepository's SQL (fanOut, sync). */
    private void fakeAssignmentsAndTasksTables() {
        when(assignmentRows.findBySourceDocumentId(anyLong())).thenAnswer(invocation -> new ArrayList<>(
                assignmentTable.values().stream().filter(assignment -> assignment.getSourceDocument() != null
                        && assignment.getSourceDocument().getId().equals(invocation.getArgument(0))).toList()));
        when(assignmentRows.saveAll(anyIterable())).thenAnswer(invocation -> storeAssignments(invocation.getArgument(0)));
        when(assignmentRows.saveAllAndFlush(anyIterable())).thenAnswer(invocation -> storeAssignments(invocation.getArgument(0)));
        when(assignmentRows.saveAndFlush(any(Assignment.class))).thenAnswer(invocation ->
                storeAssignments(List.of(invocation.<Assignment>getArgument(0))).get(0));
        doAnswer(invocation -> {
            invocation.<Iterable<Long>>getArgument(0).forEach(assignmentTable::remove);
            return null;
        }).when(assignmentRows).deleteAllByIdInBatch(anyIterable());
        when(assignmentRows.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(assignmentTable.get(invocation.<Long>getArgument(0))));
        when(assignmentRows.getReferenceById(anyLong())).thenAnswer(invocation ->
                assignmentTable.get(invocation.<Long>getArgument(0)));
        when(assignmentRows.findAllById(anyIterable())).thenAnswer(invocation -> {
            List<Assignment> found = new ArrayList<>();
            invocation.<Iterable<Long>>getArgument(0).forEach(id -> {
                if (assignmentTable.containsKey(id)) {
                    found.add(assignmentTable.get(id));
                }
            });
            return found;
        });
        when(assignmentRows.findMatchInfoByCourseId(anyLong())).thenAnswer(invocation -> assignmentTable.values().stream()
                .filter(assignment -> assignment.getCourse().getId().equals(invocation.getArgument(0)))
                .map(assignment -> new MatchInfo(assignment.getId(), assignment.getTitle(), assignment.getDeadline(),
                        assignment.getStatus()))
                .toList());
        when(assignmentRows.findViewById(anyLong())).thenAnswer(invocation -> {
            Assignment assignment = assignmentTable.get(invocation.<Long>getArgument(0));
            return Optional.of(new AssignmentResponse(assignment.getId(), assignment.getTitle(),
                    assignment.getRequirements(), assignment.getDeadline(), assignment.getStatus(), COURSE_ID,
                    inf345.getTitle(), inf345.getCode(), null, null, null, null, null, null, false, true, null));
        });

        // fanOut: one task per enrolled student for each ACTIVE assignment of the course that lacks one.
        when(taskRows.fanOut(anyLong())).thenAnswer(invocation -> {
            int created = 0;
            for (Assignment assignment : assignmentTable.values()) {
                if (assignment.getStatus() != AssignmentStatus.ACTIVE
                        || !assignment.getCourse().getId().equals(invocation.getArgument(0))) {
                    continue;
                }
                for (User user : enrolled) {
                    if (taskTable.stream().noneMatch(task -> task.getAssignment() == assignment && task.getUser() == user)) {
                        taskTable.add(Task.builder().id(ids.incrementAndGet()).title(assignment.getTitle())
                                .description(assignment.getRequirements()).deadline(assignment.getDeadline())
                                .assignment(assignment).course(inf345).user(user).build());
                        created++;
                    }
                }
            }
            return created;
        });
        // syncWithAssignments: title, description and deadline only; the status is the student's.
        when(taskRows.syncWithAssignments(anyLong())).thenAnswer(invocation -> {
            int changed = 0;
            for (Task task : taskTable) {
                Assignment assignment = task.getAssignment();
                task.setTitle(assignment.getTitle());
                task.setDescription(assignment.getRequirements());
                task.setDeadline(assignment.getDeadline());
                changed++;
            }
            return changed;
        });
        when(taskRows.findAssignmentIdsWithActivity(anyCollection())).thenAnswer(invocation -> taskTable.stream()
                .filter(task -> task.getStatus() != TaskStatus.PENDING
                        && invocation.<Collection<Long>>getArgument(0).contains(task.getAssignment().getId()))
                .map(task -> task.getAssignment().getId()).distinct().toList());
        when(taskRows.deleteByAssignmentIdIn(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> assignmentIds = invocation.getArgument(0);
            int before = taskTable.size();
            taskTable.removeIf(task -> assignmentIds.contains(task.getAssignment().getId()));
            return before - taskTable.size();
        });
    }

    private List<Assignment> storeAssignments(Iterable<Assignment> rows) {
        List<Assignment> stored = new ArrayList<>();
        for (Assignment assignment : rows) {
            if (assignment.getId() == null) {
                assignment.setId(ids.incrementAndGet());
            }
            assignmentTable.put(assignment.getId(), assignment);
            stored.add(assignment);
        }
        return stored;
    }

    /** Q&A chunks: whatever DocumentChunkService was given, one chunk per document. */
    private void fakeChunks() {
        when(chunkRows.searchInDocument(anyLong(), anyString(), anyInt())).thenAnswer(invocation ->
                chunksOf(List.of(invocation.<Long>getArgument(0)), true));
        when(chunkRows.firstChunks(anyLong(), anyInt())).thenAnswer(invocation ->
                chunksOf(List.of(invocation.<Long>getArgument(0)), true));
        when(chunkRows.searchInCourses(anyCollection(), anyBoolean(), anyString(), anyInt())).thenAnswer(invocation -> {
            Collection<Long> courseIds = invocation.getArgument(0);
            List<Long> inCourses = documentTable.values().stream()
                    .filter(document -> courseIds.contains(document.getCourse().getId())).map(Document::getId).toList();
            return chunksOf(inCourses, invocation.getArgument(1));
        });
    }

    private List<ChunkHit> chunksOf(List<Long> documentIds, boolean includeTeacherOnly) {
        List<ChunkHit> hits = new ArrayList<>();
        for (Long id : documentIds) {
            Document document = documentTable.get(id);
            String content = chunkTable.get(id);
            if (content == null || (!includeTeacherOnly && document.getVisibility() == DocumentVisibility.TEACHER_ONLY)) {
                continue;
            }
            hits.add(new ChunkHit() {
                public Long getDocumentId() { return id; }
                public String getDocumentName() { return document.getOriginalFileName(); }
                public Integer getChunkIndex() { return 0; }
                public Integer getStartOffset() { return 0; }
                public Integer getEndOffset() { return content.length(); }
                public String getContent() { return content; }
                public Double getScore() { return 1.0; }
            });
        }
        return hits;
    }
}
