package com.university.docassistant.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.university.docassistant.config.AiProperties;
import com.university.docassistant.config.JpaSettings;
import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.AiConversationDtos.ConversationAnswerResponse;
import com.university.docassistant.dto.AiConversationDtos.ConversationDetail;
import com.university.docassistant.dto.CourseWeekDtos.WeekView;
import com.university.docassistant.dto.CourseWeekDtos.WeeksOverview;
import com.university.docassistant.dto.DocumentAnswerResponse;
import com.university.docassistant.dto.SourceSnippetDto;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentType;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AiConversationRepository;
import com.university.docassistant.repository.AiConversationRepository.ConversationRow;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentSummary;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import com.university.docassistant.service.AiClientService;
import com.university.docassistant.service.AiConversationService;
import com.university.docassistant.service.AiQuestionLimiter;
import com.university.docassistant.service.AuditService;
import com.university.docassistant.service.CourseWeekPlanService;
import com.university.docassistant.service.CourseWeekService;
import com.university.docassistant.service.DocumentPrivacyService;
import com.university.docassistant.service.DocumentQnaService;
import com.university.docassistant.service.WeekSuggestionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The Sprint 5 acceptance criteria the first Sprint 5 commits left open, on PostgreSQL with the real repositories and
 * services; access and the AI are mocked. Opt-in like every *PostgresTest (README "Тесты").
 * <ul>
 *   <li>QA-AI-014 step 4 / US-14 AC-4: a conversation is still there after a reload and continues;</li>
 *   <li>QA-CRS-016 step 3 / US-16 AC-4: a hidden week and its documents are gone for the student, not the teacher;</li>
 *   <li>US-16 AC-1: assignments are counted in their weeks and keep a week from being removed;</li>
 *   <li>QA-CRS-016 step 4 / US-16 AC-5: documents filtered by category and by upload date.</li>
 * </ul>
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Import({JpaSettings.class, CourseWeekService.class, CourseWeekPlanService.class, WeekSuggestionService.class,
        AuditService.class, DocumentPrivacyService.class, AiConversationService.class, AiQuestionLimiter.class,
        Sprint5AcceptancePostgresTest.Beans.class})
@EnableConfigurationProperties(PrivacyProperties.class)
class Sprint5AcceptancePostgresTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 20, 0);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("it.db.user", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty("it.db.password", ""));
    }

    @TestConfiguration
    static class Beans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        AiProperties aiProperties() {
            return new AiProperties(true, "key", "m", "http://x", Duration.ofSeconds(1), Duration.ofSeconds(1),
                    10_000, 1, 1, 2, 1);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        }
    }

    @MockBean
    private AccessControlService access;
    @MockBean
    private AiClientService ai;
    @MockBean
    private DocumentQnaService qna;

    @Autowired
    private AiConversationService conversations;
    @Autowired
    private AiConversationRepository conversationRows;
    @Autowired
    private CourseWeekService weeks;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private User teacher;
    private User student;
    private User otherStudent;
    private Course course;

    @BeforeEach
    void setUp() {
        teacher = persist(User.builder().email("t@uni.kz").fullName("Teacher").role(Role.ROLE_TEACHER)
                .createdAt(NOW).build());
        student = persist(User.builder().email("s@uni.kz").fullName("Student").role(Role.ROLE_STUDENT)
                .createdAt(NOW).build());
        otherStudent = persist(User.builder().email("s2@uni.kz").fullName("Other").role(Role.ROLE_STUDENT)
                .createdAt(NOW).build());
        course = persist(Course.builder().title("Программная инженерия").code("INF345").teacher(teacher).build());
        for (User user : List.of(teacher, student, otherStudent)) {
            when(access.currentUser(user.getEmail())).thenReturn(user);
        }
        when(access.authorizeCourse(any(), eq(course.getId()), any(Permission.class)))
                .thenAnswer(invocation -> em.find(Course.class, course.getId()));
        when(access.authorizeDocument(any(), anyLong(), any(Permission.class)))
                .thenAnswer(invocation -> em.find(Document.class, invocation.getArgument(1, Long.class)));
    }

    // --- QA-AI-014 step 4: "Reload the page and open the chat history" - the conversation is still there.

    private final DocumentAnswerResponse lab2 = new DocumentAnswerResponse(
            "Лабораторную работу 2 нужно сдать до 03.11.2026 18:00 [syllabus.pdf, фрагмент 1].", true,
            List.of(new SourceSnippetDto(1, 7L, "syllabus.pdf", null, "Программная инженерия", 0, 120, 180,
                    "Лабораторная работа 2 — 3 ноября 2026, 18:00")),
            List.of(), List.of(), true);

    @Test
    void aConversationIsSavedListedReadAgainAndContinued() {
        when(qna.askCourse(eq(course.getId()), anyString(), eq("s@uni.kz"), anyList())).thenReturn(lab2);

        ConversationAnswerResponse first = conversations.ask("Когда сдавать лабораторную работу 2?",
                course.getId(), null, "s@uni.kz");
        ConversationAnswerResponse second = conversations.ask("А эссе?", null, first.conversationId(),
                "s@uni.kz");
        flushAndClear();

        assertThat(second.conversationId()).isEqualTo(first.conversationId());
        // The follow-up kept the course of the conversation and saw the first turn.
        org.mockito.Mockito.verify(qna).askCourse(course.getId(), "А эссе?", "s@uni.kz",
                List.of(new DocumentQnaService.PriorTurn("Когда сдавать лабораторную работу 2?", lab2.answer())));

        List<ConversationRow> history = conversations.list("s@uni.kz", PageRequest.of(0, 20)).content();
        assertThat(history).singleElement().satisfies(row -> {
            assertThat(row.title()).isEqualTo("Когда сдавать лабораторную работу 2?");
            assertThat(row.courseCode()).isEqualTo("INF345");
            assertThat(row.turnCount()).isEqualTo(2L);
        });

        ConversationDetail detail = conversations.get(first.conversationId(), "s@uni.kz");
        assertThat(detail.turns()).extracting(turn -> turn.question())
                .containsExactly("Когда сдавать лабораторную работу 2?", "А эссе?");
        assertThat(detail.turns().get(0).answer()).isEqualTo(lab2);

        // Nobody else sees it, and the owner can delete it with its turns.
        assertThat(conversations.list("s2@uni.kz", PageRequest.of(0, 20)).content()).isEmpty();
        assertThat(catchThrowableOfType(() -> conversations.get(first.conversationId(), "s2@uni.kz"),
                ApiException.class).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(catchThrowableOfType(() -> conversations.delete(first.conversationId(), "s2@uni.kz"),
                ApiException.class).getCode()).isEqualTo(ErrorCodes.CONVERSATION_NOT_FOUND);
        conversations.delete(first.conversationId(), "s@uni.kz");
        assertThat(count("ai_conversation_turns")).isZero();
        assertThat(count("ai_conversations")).isZero();
    }

    @Test
    void conversationsGoWithTheirCourseAndTheirUser() {
        when(qna.askCourse(eq(course.getId()), anyString(), anyString(), anyList())).thenReturn(lab2);
        when(qna.askAll(anyString(), anyString(), anyList())).thenReturn(lab2);
        conversations.ask("По курсу", course.getId(), null, "s@uni.kz");
        conversations.ask("По всем курсам", null, null, "s@uni.kz");
        conversations.ask("Другой студент", null, null, "s2@uni.kz");
        flushAndClear();
        assertThat(count("ai_conversations")).isEqualTo(3);

        jdbc.update("DELETE FROM courses WHERE id = ?", course.getId());
        assertThat(conversationRows.findRowsByUserId(student.getId(), PageRequest.of(0, 20)).getContent())
                .extracting(ConversationRow::title).containsExactly("По всем курсам");

        jdbc.update("DELETE FROM users WHERE id = ?", student.getId());
        assertThat(count("ai_conversations")).isEqualTo(1);
        assertThat(count("ai_conversation_turns")).isEqualTo(1);
    }

    // --- QA-CRS-016 step 3: "Hide Week 6 and open the course as a student" - sections in order without week 6.

    @Test
    void aHiddenWeekAndItsDocumentsAreGoneForTheStudentOnly() {
        Document week4 = document("UML.pdf", DocumentCategory.LECTURE, 4, NOW.minusDays(10));
        Document week6 = document("Patterns.pdf", DocumentCategory.LECTURE, 6, NOW.minusDays(3));
        Document loose = document("Regulation.pdf", DocumentCategory.REGULATION, null, NOW.minusDays(1));
        flushAndClear();

        WeekView hidden = weeks.setHidden(course.getId(), 6, true, "t@uni.kz");
        assertThat(hidden.hidden()).isTrue();
        assertThat(hidden.documentCount()).isEqualTo(1);
        flushAndClear();

        WeeksOverview forStudent = weeks.weeks(course.getId(), "s@uni.kz");
        assertThat(forStudent.weeks()).extracting(WeekView::week).containsExactly(4);
        assertThat(forStudent.weeks()).allSatisfy(week -> assertThat(week.hidden()).isNull());
        WeeksOverview forTeacher = weeks.weeks(course.getId(), "t@uni.kz");
        assertThat(forTeacher.weeks()).hasSize(15);
        assertThat(forTeacher.weeks().get(5).hidden()).isTrue();
        assertThat(forTeacher.weeks().get(5).documentCount()).isEqualTo(1);

        List<Long> ids = List.of(course.getId());
        assertThat(listed(documents.findSummariesInCourses(ids, false, course.getId(), false, null, false,
                PageRequest.of(0, 20)).getContent()))
                .as("the student's list").containsExactlyInAnyOrder(week4.getId(), loose.getId());
        assertThat(listed(documents.findSummariesInCourses(ids, true, course.getId(), false, null, false,
                PageRequest.of(0, 20)).getContent()))
                .as("the teacher's list").containsExactlyInAnyOrder(week4.getId(), week6.getId(), loose.getId());
        assertThat(documents.isInHiddenWeek(week6.getId())).isTrue();
        assertThat(documents.isInHiddenWeek(week4.getId())).isFalse();
        assertThat(documents.isInHiddenWeek(loose.getId())).isFalse();
        assertThat(documents.findIdsInHiddenWeeks(List.of(week4.getId(), week6.getId(), loose.getId())))
                .containsExactly(week6.getId());

        // Shown again: back for the student, the topic and the plan untouched.
        weeks.setHidden(course.getId(), 6, false, "t@uni.kz");
        flushAndClear();
        assertThat(weeks.weeks(course.getId(), "s@uni.kz").weeks()).extracting(WeekView::week)
                .containsExactly(4, 6);
        assertThat(documents.isInHiddenWeek(week6.getId())).isFalse();
    }

    @Test
    void aSyllabusPlanDoesNotShowAHiddenWeekAgain() {
        weeks.setHidden(course.getId(), 3, true, "t@uni.kz");
        flushAndClear();
        jdbc.update("""
                INSERT INTO course_weeks (course_id, week_number, topic, source_document_id, edited_by_teacher)
                VALUES (?, 3, 'Old', NULL, false)
                ON CONFLICT (course_id, week_number) DO UPDATE SET topic = EXCLUDED.topic
                """, course.getId());
        assertThat(jdbc.queryForObject("SELECT hidden FROM course_weeks WHERE course_id = ? AND week_number = 3",
                Boolean.class, course.getId())).isTrue();
    }

    // --- US-16 AC-1: assignments in the weeks.

    @Test
    void assignmentsAreCountedInTheirWeeksAndHoldTheWeeksCount() {
        assignment("Лабораторная работа 1", 2);
        assignment("Лабораторная работа 2", 9);
        assignment("Эссе", null);
        flushAndClear();

        WeeksOverview forStudent = weeks.weeks(course.getId(), "s@uni.kz");
        // A week with only an assignment is still a section for the student.
        assertThat(forStudent.weeks()).extracting(WeekView::week, WeekView::assignmentCount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, 1L),
                        org.assertj.core.groups.Tuple.tuple(9, 1L));
        assertThat(forStudent.withoutWeekAssignmentCount()).isEqualTo(1);

        weeks.setHidden(course.getId(), 9, true, "t@uni.kz");
        flushAndClear();
        assertThat(weeks.weeks(course.getId(), "s@uni.kz").weeks()).extracting(WeekView::week).containsExactly(2);

        ApiException inUse = catchThrowableOfType(() -> weeks.updateWeeksCount(course.getId(), 8, "t@uni.kz"),
                ApiException.class);
        assertThat(inUse.getCode()).isEqualTo(ErrorCodes.WEEK_IN_USE);
        assertThat(weeks.updateWeeksCount(course.getId(), 9, "t@uni.kz").weeksCount()).isEqualTo(9);
    }

    // --- QA-CRS-016 step 4: "Filter the documents by category Лекция" - only lectures; and by upload date.

    @Test
    void documentsAreFilteredByCategoryAndUploadDate() {
        Document lecture = document("UML.pdf", DocumentCategory.LECTURE, 4, LocalDateTime.of(2026, 11, 2, 10, 0));
        Document lateLecture = document("Patterns.pdf", DocumentCategory.LECTURE, null,
                LocalDateTime.of(2026, 11, 10, 23, 59));
        Document syllabus = document("Syllabus.pdf", DocumentCategory.SYLLABUS, null,
                LocalDateTime.of(2026, 11, 5, 9, 0));
        flushAndClear();
        List<Long> ids = List.of(course.getId());

        assertThat(listed(documents.findSummariesInCourses(ids, false, course.getId(), false, null, false, false,
                DocumentCategory.LECTURE, DocumentRepository.NO_START, DocumentRepository.NO_END,
                PageRequest.of(0, 20)).getContent()))
                .containsExactlyInAnyOrder(lecture.getId(), lateLecture.getId());
        // 5..10 November, both days included (the service passes the end as the next midnight).
        assertThat(listed(documents.findSummariesInCourses(ids, false, course.getId(), false, null, false, true,
                DocumentCategory.OTHER, LocalDateTime.of(2026, 11, 5, 0, 0), LocalDateTime.of(2026, 11, 11, 0, 0),
                PageRequest.of(0, 20)).getContent()))
                .containsExactlyInAnyOrder(lateLecture.getId(), syllabus.getId());
        assertThat(documents.findAllSummaries(course.getId(), false, null, false, false, DocumentCategory.SYLLABUS,
                DocumentRepository.NO_START, LocalDateTime.of(2026, 11, 5, 0, 0), PageRequest.of(0, 20))
                .getTotalElements()).as("no syllabus before 5 November").isZero();
    }

    private Document document(String name, DocumentCategory category, Integer week, LocalDateTime uploadedAt) {
        return persist(Document.builder().originalFileName(name).storedFileName(UUID.randomUUID().toString())
                .contentType("application/pdf").fileSize(1L).category(category).type(DocumentType.OTHER)
                .aiStatus(AiStatus.DONE).visibility(DocumentVisibility.COURSE_STUDENTS).course(course).owner(teacher)
                .maskedText("Текст").weekNumber(week).uploadedAt(uploadedAt).build());
    }

    private Assignment assignment(String title, Integer week) {
        return persist(Assignment.builder().title(title).course(course).weekNumber(week).build());
    }

    private static List<Long> listed(List<DocumentSummary> rows) {
        return rows.stream().map(DocumentSummary::id).toList();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
