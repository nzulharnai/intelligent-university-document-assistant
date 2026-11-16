package com.university.docassistant.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.university.docassistant.config.AiProperties;
import com.university.docassistant.config.AsyncConfig;
import com.university.docassistant.config.DocumentSearchSchema;
import com.university.docassistant.config.JpaSettings;
import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.AnswerTaskDto;
import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.AssignmentDtos.CreateAssignmentRequest;
import com.university.docassistant.dto.AssignmentDtos.UpdateAssignmentRequest;
import com.university.docassistant.dto.DocumentAnswerResponse;
import com.university.docassistant.dto.DocumentResponse;
import com.university.docassistant.dto.DocumentTextResponse;
import com.university.docassistant.dto.SourceSnippetDto;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentType;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.JoinAttemptLimiter;
import com.university.docassistant.service.AiClientService;
import com.university.docassistant.service.AssignmentEvents;
import com.university.docassistant.service.AssignmentService;
import com.university.docassistant.service.AuditService;
import com.university.docassistant.service.CoursePrivacyRemask;
import com.university.docassistant.service.CourseWeekPlanService;
import com.university.docassistant.service.CourseWeekService;
import com.university.docassistant.service.DocumentAnalysisService;
import com.university.docassistant.service.DocumentChunkService;
import com.university.docassistant.service.DocumentChunker;
import com.university.docassistant.service.DocumentClassificationService;
import com.university.docassistant.service.DocumentParserService;
import com.university.docassistant.service.DocumentPrivacyService;
import com.university.docassistant.service.DocumentProcessingService;
import com.university.docassistant.service.DocumentQnaService;
import com.university.docassistant.service.DocumentService;
import com.university.docassistant.service.EmailService;
import com.university.docassistant.service.NotificationEvents;
import com.university.docassistant.service.NotificationService;
import com.university.docassistant.service.WeekSuggestionService;
import com.university.docassistant.service.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 5 acceptance cases (QA-ASK-014, QA-SRC-015, QA-WEEK-016) end to end on PostgreSQL: the real services,
 * access control, full-text search, chunking and transactions, with real commits (no test transaction; the tables
 * are emptied after each case). Only the AI (AiClientService), the mail, the file storage and the upload pipeline
 * are mocks; the clock is fixed. Opt-in like the other *PostgresTest classes; only ever the separate
 * doc_assistant_test database (README "Тесты").
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnableConfigurationProperties(PrivacyProperties.class)
@Import({JpaSettings.class, Sprint5QaPostgresTest.Fixed.class, AccessControlService.class, AuditService.class,
        DocumentPrivacyService.class, AssignmentService.class, AssignmentEvents.class, NotificationService.class,
        NotificationEvents.class, JoinAttemptLimiter.class, DocumentService.class, DocumentChunkService.class,
        DocumentChunker.class, DocumentQnaService.class, CourseWeekService.class, CourseWeekPlanService.class,
        WeekSuggestionService.class})
class Sprint5QaPostgresTest {

    /** Friday 13.11.2026 12:00 in the app time zone. */
    static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 12, 0);

    private static final String TEACHER = "o.kim@uni.kz";
    private static final String OTHER_TEACHER = "a.nur@uni.kz";
    private static final String STUDENT = "student@uni.kz";
    private static final String CLASSMATE = "classmate@uni.kz";
    private static final String IIN = "990101300013";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("it.db.user", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty("it.db.password", ""));
    }

    @TestConfiguration
    static class Fixed {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        }

        @Bean(name = AsyncConfig.MAIL_EXECUTOR)
        TaskExecutor mailTaskExecutor() {
            return new SyncTaskExecutor();
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AiProperties aiProperties() {
            return new AiProperties(true, "key", "m", "http://x", Duration.ofSeconds(1), Duration.ofSeconds(1),
                    15_000, 1, 1, 2, 1);
        }
    }

    @MockBean
    private AiClientService ai;
    @MockBean
    private EmailService email;
    @MockBean
    private StorageService storage;
    @MockBean
    private CoursePrivacyRemask privacyRemask;
    @MockBean
    private DocumentParserService parser;
    @MockBean
    private DocumentAnalysisService analysis;
    @MockBean
    private DocumentClassificationService classification;
    @MockBean
    private DocumentProcessingService processing;

    @Autowired
    private DocumentQnaService qna;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentChunkService chunks;
    @Autowired
    private DocumentPrivacyService privacy;
    @Autowired
    private AssignmentService assignments;
    @Autowired
    private CourseWeekService weeks;
    @Autowired
    private CourseWeekPlanService plans;
    @Autowired
    private WeekSuggestionService suggestions;
    @Autowired
    private UserRepository users;
    @Autowired
    private CourseRepository courses;
    @Autowired
    private EnrollmentRepository enrollments;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private TransactionTemplate transaction;
    @Autowired
    private JdbcTemplate jdbc;

    private User teacher;
    private Course course;
    private Course otherCourse;

    @BeforeEach
    void setUp() {
        new DocumentSearchSchema(jdbc).run(null);
        // The nested classes share the context, so the AI mock is reset here, before every case.
        org.mockito.Mockito.reset(ai);
        when(ai.isConfigured()).thenReturn(true);
        transaction.executeWithoutResult(status -> {
            teacher = users.save(user(TEACHER, Role.ROLE_TEACHER));
            User other = users.save(user(OTHER_TEACHER, Role.ROLE_TEACHER));
            User student = users.save(user(STUDENT, Role.ROLE_STUDENT));
            User classmate = users.save(user(CLASSMATE, Role.ROLE_STUDENT));
            course = courses.save(Course.builder().title("Базы данных").code("INF210").teacher(teacher).build());
            otherCourse = courses.save(Course.builder().title("Чужой курс").code("XXX1").teacher(other).build());
            enrollments.enroll(course.getId(), student.getId(), NOW.minusDays(60));
            enrollments.enroll(course.getId(), classmate.getId(), NOW.minusDays(60));
        });
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE notifications, task_submissions, tasks, assignments, document_chunks, course_weeks, "
                + "documents, course_students, courses, audit_log, users RESTART IDENTITY CASCADE");
    }

    @Nested
    @DisplayName("QA-ASK-014: questions about the courses in plain language")
    class QaAsk014 {

        @BeforeEach
        void documents() {
            document(course, "labs.pdf", DocumentCategory.ASSIGNMENT, DocumentVisibility.COURSE_STUDENTS,
                    "Лабораторная работа 1 сдаётся до 10 ноября.\n\nЛабораторная работа 2 сдаётся до 1 декабря.");
            document(course, "exam-key.pdf", DocumentCategory.EXAM_ANSWERS, DocumentVisibility.TEACHER_ONLY,
                    "Ответы к экзамену и лабораторной работе 2: вариант А.");
            document(otherCourse, "foreign.pdf", DocumentCategory.LECTURE, DocumentVisibility.COURSE_STUDENTS,
                    "Лабораторная работа 2 чужого курса сдаётся в январе.");
        }

        @Test
        @DisplayName("a course question and an all-courses question send only the student's courses, never TEACHER_ONLY")
        void onlyAllowedCourses() {
            when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("До 1 декабря [1]."));

            qna.askCourse(course.getId(), "Когда сдавать лабораторную 2?", STUDENT);
            qna.askAll("Когда сдавать лабораторную 2?", STUDENT);

            ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
            verify(ai, org.mockito.Mockito.times(2)).chat(system.capture(), anyString());
            assertThat(system.getAllValues()).allSatisfy(prompt -> assertThat(prompt)
                    .contains("Лабораторная работа 2 сдаётся до 1 декабря.")
                    .doesNotContain("Ответы к экзамену", "чужого курса", "Чужой курс", "foreign.pdf"));
        }

        @Test
        @DisplayName("\"лабораторная 2\" puts the fragment about lab 2 first")
        void numberFindsTheRightFragment() {
            String lab1 = "Лабораторная работа 1 сдаётся до 10 ноября. " + "Подробности первой работы. ".repeat(40);
            String lab2 = "Лабораторная работа 2 сдаётся до 1 декабря. " + "Подробности второй работы. ".repeat(40);
            document(course, "labs-long.pdf", DocumentCategory.ASSIGNMENT, DocumentVisibility.COURSE_STUDENTS,
                    lab1 + "\n\n" + lab2);
            when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("До 1 декабря."));

            DocumentAnswerResponse response = qna.askCourse(course.getId(), "лабораторная 2", STUDENT);

            // Among the fragments of the long file, the one about lab 2 ranks first.
            assertThat(response.sources()).filteredOn(source -> source.documentName().equals("labs-long.pdf"))
                    .first().satisfies(source -> assertThat(source.text()).contains("Лабораторная работа 2"));
        }

        @Test
        @DisplayName("a moved deadline reaches the AI in the assignments block")
        void movedDeadline() {
            AssignmentResponse lab = assignments.create(course.getId(),
                    new CreateAssignmentRequest("Лабораторная работа 2", null, LocalDateTime.of(2026, 12, 1, 23, 59)),
                    TEACHER);
            assignments.update(lab.id(), new UpdateAssignmentRequest("Лабораторная работа 2", null,
                    LocalDateTime.of(2026, 12, 8, 23, 59)), TEACHER);
            when(ai.chat(anyString(), anyString())).thenReturn(Optional.of(
                    "Срок изменён: до 08.12.2026 23:59 [задание 1] (в документе — 1 декабря [1])."));

            DocumentAnswerResponse response = qna.askCourse(course.getId(), "Когда сдавать лабораторную 2?", STUDENT);

            ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
            verify(ai).chat(system.capture(), anyString());
            assertThat(system.getValue()).contains("[АКТУАЛЬНЫЕ ЗАДАНИЯ КУРСА]",
                    "Название: Лабораторная работа 2 · Срок: 08.12.2026 23:59", "верна дата из блока");
            assertThat(response.assignments()).singleElement().satisfies(cited -> {
                assertThat(cited.assignmentId()).isEqualTo(lab.id());
                assertThat(cited.deadline()).isEqualTo(LocalDateTime.of(2026, 12, 8, 23, 59));
            });
        }

        @Test
        @DisplayName("\"какие у меня пропущенные задания?\": the student's own tasks with their states, the date, nobody else's")
        void ownTasks() {
            AssignmentResponse overdue = assignments.create(course.getId(),
                    new CreateAssignmentRequest("Эссе", null, NOW.minusDays(2)), TEACHER);
            AssignmentResponse handedIn = assignments.create(course.getId(),
                    new CreateAssignmentRequest("Тест 1", null, NOW.minusDays(1)), TEACHER);
            Long studentId = users.findByEmail(STUDENT).orElseThrow().getId();
            jdbc.update("UPDATE tasks SET status = 'SUBMITTED', submitted_at = ? WHERE user_id = ? AND assignment_id = ?",
                    NOW.minusDays(3), studentId, handedIn.id());
            Long classmateId = users.findByEmail(CLASSMATE).orElseThrow().getId();
            jdbc.update("UPDATE tasks SET title = 'Эссе одноклассника' WHERE user_id = ?", classmateId);
            when(ai.chat(anyString(), anyString())).thenReturn(Optional.of(
                    "Просрочено эссе [задача 1]. Тест 1 сдан [задача 2]."));

            DocumentAnswerResponse response = qna.askAll("Какие у меня пропущенные задания или дедлайны?", STUDENT);

            ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
            verify(ai).chat(system.capture(), anyString());
            assertThat(system.getValue()).contains("Сейчас: 13.11.2026 12:00.", "[МОИ ЗАДАЧИ]",
                    "Название: Эссе · Срок: 11.11.2026 12:00 · Состояние: просрочено",
                    "Название: Тест 1 · Срок: 12.11.2026 12:00 · Состояние: сдано")
                    .doesNotContain("Эссе одноклассника");
            assertThat(response.hasAnswer()).isTrue();
            assertThat(response.tasks()).extracting(AnswerTaskDto::title, AnswerTaskDto::state)
                    .containsExactly(tuple("Эссе", "OVERDUE"), tuple("Тест 1", "SUBMITTED"));

            clearInvocations(ai);
            qna.askAll("Какие у меня пропущенные задания?", TEACHER);
            verify(ai).chat(system.capture(), anyString());
            assertThat(system.getValue()).contains("личных задач у него нет").doesNotContain("[МОИ ЗАДАЧИ]\n")
                    .contains("Название: Эссе · Срок: 11.11.2026 12:00");
            assertThat(overdue.id()).isNotNull();
        }

        @Test
        @DisplayName("another course is 403 before anything is read; AI off is 503")
        void accessAndAiOff() {
            assertThatThrownBy(() -> qna.askCourse(otherCourse.getId(), "Когда экзамен?", STUDENT))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.COURSE_NOT_ENROLLED);
                    });
            verify(ai, never()).chat(anyString(), anyString());

            when(ai.isConfigured()).thenReturn(false);
            assertThatThrownBy(() -> qna.askAll("Когда экзамен?", STUDENT))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.AI_NOT_CONFIGURED);
                    });
        }
    }

    @Nested
    @DisplayName("QA-SRC-015: where an answer comes from")
    class QaSrc015 {

        private Long syllabus;

        @BeforeEach
        void document() {
            syllabus = Sprint5QaPostgresTest.this.document(course, "syllabus.pdf", DocumentCategory.SYLLABUS,
                    DocumentVisibility.COURSE_STUDENTS, "Контакты: студент Иванов, ИИН " + IIN + ".\n\n"
                            + "Экзамен по базам данных пройдёт 25 декабря в аудитории 301.");
        }

        @Test
        @DisplayName("sources carry the document, the course and offsets into the masked text the student gets")
        void offsetsPointIntoTheMaskedText() {
            when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("25 декабря [1]."));

            DocumentAnswerResponse response = qna.askCourse(course.getId(), "Когда экзамен?", STUDENT);
            DocumentTextResponse forStudent = documentService.text(syllabus, STUDENT);
            DocumentTextResponse maskedForTeacher = documentService.text(syllabus, TEACHER, true);

            assertThat(response.citedExplicitly()).isTrue();
            SourceSnippetDto source = response.sources().get(0);
            assertThat(source.documentId()).isEqualTo(syllabus);
            assertThat(source.courseId()).isEqualTo(course.getId());
            assertThat(source.courseTitle()).isEqualTo("Базы данных");
            assertThat(forStudent.text()).doesNotContain(IIN);
            assertThat(forStudent.text().substring(source.startOffset(), source.endOffset())).isEqualTo(source.text());
            assertThat(maskedForTeacher.text()).isEqualTo(forStudent.text());
            assertThat(documentService.text(syllabus, TEACHER).text()).as("the original without the parameter").contains(IIN);
        }

        @Test
        @DisplayName("an answer without citations says so and shows the whole context")
        void notCitedExplicitly() {
            when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Экзамен 25 декабря."));

            DocumentAnswerResponse response = qna.askCourse(course.getId(), "Когда экзамен?", STUDENT);

            assertThat(response.citedExplicitly()).isFalse();
            assertThat(response.sources()).isNotEmpty().allSatisfy(source -> assertThat(source.documentId()).isEqualTo(syllabus));
        }
    }

    @Nested
    @DisplayName("QA-WEEK-016: course documents by week")
    class QaWeek016 {

        private Long syllabus;
        private Long lecture;

        @BeforeEach
        void plan() {
            syllabus = document(course, "syllabus.pdf", DocumentCategory.SYLLABUS, DocumentVisibility.COURSE_STUDENTS,
                    "Календарный план. Неделя 1 — введение. Неделя 2 — нормализация. Неделя 3 — SQL. " + "Текст. ".repeat(20));
            lecture = document(course, "normal-forms.pdf", DocumentCategory.LECTURE, DocumentVisibility.COURSE_STUDENTS,
                    "Нормальные формы: 1НФ, 2НФ, 3НФ и функциональные зависимости. " + "Текст. ".repeat(20));
            when(ai.chatJson(anyString(), anyString())).thenAnswer(invocation -> {
                String system = invocation.getArgument(0);
                return Optional.of(system.contains("календарный план")
                        ? "{\"weeks\": [{\"week\": 1, \"topic\": \"Введение\"}, {\"week\": 2, \"topic\": \"Нормализация\"},"
                        + " {\"week\": 3, \"topic\": \"SQL\"}]}"
                        : "{\"week\": 2, \"confidence\": 0.86}");
            });
            plans.afterDocumentProcessed(syllabus);
        }

        @Test
        @DisplayName("the syllabus gives the plan, the lecture a hint the student never sees; accepting it moves the document")
        void planHintAndAccept() {
            assertThat(jdbc.queryForList("SELECT topic FROM course_weeks WHERE course_id = ? ORDER BY week_number",
                    String.class, course.getId())).containsExactly("Введение", "Нормализация", "SQL");
            assertThat(suggestedWeek(lecture)).isEqualTo(2);

            List<DocumentResponse> forStudent = documentService.list(STUDENT, course.getId(), false, null,
                    PageRequest.of(0, 20)).content();
            assertThat(forStudent).extracting(DocumentResponse::suggestedWeek).containsOnlyNulls();
            assertThat(weeks.weeks(course.getId(), STUDENT).pendingSuggestions()).isNull();
            assertThat(weeks.weeks(course.getId(), TEACHER).pendingSuggestions()).isEqualTo(1);

            weeks.acceptSuggestion(lecture, TEACHER);

            assertThat(weekNumber(lecture)).isEqualTo(2);
            assertThat(documentService.list(STUDENT, course.getId(), false, "2", PageRequest.of(0, 20)).content())
                    .extracting(DocumentResponse::id).containsExactly(lecture);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'DOCUMENT_WEEK_SET' "
                    + "AND details LIKE '%AI suggestion accepted%'", Long.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("a week set by hand is never overwritten; accept-all takes the open hints")
        void handWeekAndAcceptAll() {
            weeks.setDocumentWeek(lecture, 3, TEACHER);
            assertThat(suggestions.suggest(lecture)).isEmpty();
            plans.afterDocumentProcessed(lecture);
            assertThat(weekNumber(lecture)).isEqualTo(3);
            assertThat(suggestedWeek(lecture)).isNull();

            Long notes = document(course, "notes-week-1.pdf", DocumentCategory.OTHER, DocumentVisibility.COURSE_STUDENTS,
                    "Неделя 1. Заметки к введению. " + "Текст. ".repeat(20));
            plans.afterDocumentProcessed(notes);
            assertThat(suggestedWeek(notes)).as("explicit week, no AI needed").isEqualTo(1);

            assertThat(weeks.acceptAll(course.getId(), TEACHER).accepted()).isEqualTo(1);
            assertThat(weekNumber(notes)).isEqualTo(1);
        }

        @Test
        @DisplayName("students and other teachers cannot change weeks; the count cannot drop below a used week")
        void rightsAndWeekInUse() {
            for (String email : List.of(STUDENT, OTHER_TEACHER)) {
                assertThatThrownBy(() -> weeks.acceptSuggestion(lecture, email))
                        .isInstanceOfSatisfying(ApiException.class,
                                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
                assertThatThrownBy(() -> weeks.updateWeeksCount(course.getId(), 5, email))
                        .isInstanceOfSatisfying(ApiException.class,
                                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            }
            assertThat(suggestedWeek(lecture)).isEqualTo(2);

            weeks.setDocumentWeek(lecture, 3, TEACHER);
            assertThatThrownBy(() -> weeks.updateWeeksCount(course.getId(), 2, TEACHER))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.WEEK_IN_USE);
                    });
            assertThat(weeks.updateWeeksCount(course.getId(), 3, TEACHER).weeksCount()).isEqualTo(3);
        }

        private Integer suggestedWeek(Long id) {
            return jdbc.queryForObject("SELECT suggested_week FROM documents WHERE id = ?", Integer.class, id);
        }

        private Integer weekNumber(Long id) {
            return jdbc.queryForObject("SELECT week_number FROM documents WHERE id = ?", Integer.class, id);
        }
    }

    /** A processed document: masked like an upload, chunked for Q&A by the real DocumentChunkService. */
    private Long document(Course owner, String name, DocumentCategory category, DocumentVisibility visibility,
                          String text) {
        User courseTeacher = owner.getId().equals(course.getId()) ? teacher
                : users.findByEmail(OTHER_TEACHER).orElseThrow();
        DocumentPrivacyService.PreparedText prepared = privacy.prepare(text, courseTeacher.getEmail(), null);
        Document saved = transaction.execute(status -> documents.save(Document.builder().originalFileName(name)
                .storedFileName(UUID.randomUUID().toString().replace("-", "")).contentType("application/pdf")
                .fileSize(1L).category(category).type(DocumentType.fromCategory(category)).aiStatus(AiStatus.DONE)
                .visibility(visibility).course(owner).owner(courseTeacher).extractedText(text)
                .maskedText(prepared.masked().text()).piiCount(prepared.masked().count())
                .textSnippet(prepared.snippet()).uploadedAt(NOW).build()));
        chunks.index(saved.getId(), prepared.masked().text());
        return saved.getId();
    }

    private static User user(String email, Role role) {
        return User.builder().email(email).fullName(email).role(role).createdAt(LocalDateTime.now()).build();
    }
}
