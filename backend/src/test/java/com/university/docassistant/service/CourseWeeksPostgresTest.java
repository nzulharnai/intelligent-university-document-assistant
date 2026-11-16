package com.university.docassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.university.docassistant.config.AiProperties;
import com.university.docassistant.config.JpaSettings;
import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.CourseWeekDtos.WeekView;
import com.university.docassistant.dto.CourseWeekDtos.WeeksOverview;
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
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentSummary;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * US-16 on PostgreSQL: the week filter of the document lists, the counts a student gets, accept-all, WEEK_IN_USE,
 * the plan upsert (a teacher's topic wins) and the plan surviving its syllabus. The services are real; access and the
 * AI are mocked. Opt-in like every *PostgresTest (README "Тесты"), only ever against doc_assistant_test.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Import({JpaSettings.class, CourseWeekService.class, CourseWeekPlanService.class, WeekSuggestionService.class,
        AuditService.class, DocumentPrivacyService.class, CourseWeeksPostgresTest.Beans.class})
@EnableConfigurationProperties(PrivacyProperties.class)
class CourseWeeksPostgresTest {

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
            return new ObjectMapper();
        }

        @Bean
        AiProperties aiProperties() {
            return new AiProperties(true, "key", "m", "http://x", Duration.ofSeconds(1), Duration.ofSeconds(1),
                    10_000, 1, 1, 2, 1);
        }
    }

    @MockBean
    private AccessControlService access;
    @MockBean
    private AiClientService ai;

    @Autowired
    private CourseWeekService weeks;
    @Autowired
    private CourseWeekPlanService plans;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private User teacher;
    private User student;
    private Course course;

    @BeforeEach
    void setUp() {
        teacher = persist(User.builder().email("t@uni.kz").fullName("Teacher").role(Role.ROLE_TEACHER)
                .createdAt(LocalDateTime.now()).build());
        student = persist(User.builder().email("s@uni.kz").fullName("Student").role(Role.ROLE_STUDENT)
                .createdAt(LocalDateTime.now()).build());
        course = persist(Course.builder().title("Базы данных").code("DB").teacher(teacher).build());
        for (User user : List.of(teacher, student)) {
            when(access.currentUser(user.getEmail())).thenReturn(user);
        }
        when(access.authorizeCourse(any(), eq(course.getId()), any(Permission.class)))
                .thenAnswer(invocation -> em.find(Course.class, course.getId()));
        when(access.authorizeDocument(any(), anyLong(), any(Permission.class)))
                .thenAnswer(invocation -> em.find(Document.class, invocation.getArgument(1, Long.class)));
    }

    @Test
    void theNewCourseHasFifteenWeeks() {
        flushAndClear();
        assertThat(jdbc.queryForObject("SELECT weeks_count FROM courses WHERE id = ?", Integer.class, course.getId()))
                .isEqualTo(15);
    }

    @Test
    void listsFilterByTheConfirmedWeekAndByNone() {
        Document lab = document("lab-2.pdf", DocumentCategory.ASSIGNMENT, DocumentVisibility.COURSE_STUDENTS, 2, null);
        Document key = document("key-2.pdf", DocumentCategory.EXAM_ANSWERS, DocumentVisibility.TEACHER_ONLY, 2, null);
        Document loose = document("notes.pdf", DocumentCategory.OTHER, DocumentVisibility.COURSE_STUDENTS, null, 2);
        document("lecture-5.pdf", DocumentCategory.LECTURE, DocumentVisibility.COURSE_STUDENTS, 5, null);
        flushAndClear();
        List<Long> ids = List.of(course.getId());

        assertThat(listed(documents.findSummariesInCourses(ids, false, course.getId(), false, 2, false,
                PageRequest.of(0, 10)).getContent())).as("student, week 2").containsExactly(lab.getId());
        assertThat(listed(documents.findSummariesInCourses(ids, true, course.getId(), false, 2, false,
                PageRequest.of(0, 10)).getContent())).as("teacher, week 2").containsExactlyInAnyOrder(lab.getId(), key.getId());
        assertThat(listed(documents.findSummariesInCourses(ids, false, course.getId(), false, null, true,
                PageRequest.of(0, 10)).getContent())).as("a suggestion is not a week").containsExactly(loose.getId());
        assertThat(documents.findAllSummaries(course.getId(), false, null, true, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
        assertThat(documents.findAllSummaries(course.getId(), false, null, false, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(4);
    }

    @Test
    void aStudentCountsOnlyWhatTheyMaySee() {
        document("lab-2.pdf", DocumentCategory.ASSIGNMENT, DocumentVisibility.COURSE_STUDENTS, 2, null);
        document("key-2.pdf", DocumentCategory.EXAM_ANSWERS, DocumentVisibility.TEACHER_ONLY, 2, null);
        document("key-7.pdf", DocumentCategory.EXAM_ANSWERS, DocumentVisibility.TEACHER_ONLY, 7, null);
        Document pending = document("new.pdf", DocumentCategory.OTHER, DocumentVisibility.COURSE_STUDENTS, null, 3);
        pending.setAiStatus(AiStatus.PENDING);
        document("notes.pdf", DocumentCategory.OTHER, DocumentVisibility.COURSE_STUDENTS, null, 4);
        flushAndClear();

        WeeksOverview forStudent = weeks.weeks(course.getId(), "s@uni.kz");
        WeeksOverview forTeacher = weeks.weeks(course.getId(), "t@uni.kz");

        assertThat(forStudent.weeks()).extracting(WeekView::week, WeekView::documentCount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, 1L));
        assertThat(forStudent.withoutWeekCount()).isEqualTo(1);
        assertThat(forStudent.pendingSuggestions()).isNull();
        assertThat(forTeacher.weeks()).hasSize(15);
        assertThat(forTeacher.weeks().get(1).documentCount()).isEqualTo(2);
        assertThat(forTeacher.weeks().get(6).documentCount()).isEqualTo(1);
        assertThat(forTeacher.withoutWeekCount()).isEqualTo(2);
        assertThat(forTeacher.pendingSuggestions()).isEqualTo(2);
    }

    @Test
    void acceptAllTakesEveryOpenSuggestionWithinTheWeeks() {
        Document notes = document("notes.pdf", DocumentCategory.OTHER, DocumentVisibility.COURSE_STUDENTS, null, 3);
        Document slides = document("slides.pdf", DocumentCategory.LECTURE, DocumentVisibility.COURSE_STUDENTS, null, 9);
        Document stale = document("old.pdf", DocumentCategory.LECTURE, DocumentVisibility.COURSE_STUDENTS, null, 20);
        Document fixed = document("fixed.pdf", DocumentCategory.LECTURE, DocumentVisibility.COURSE_STUDENTS, 1, null);
        flushAndClear();

        assertThat(weeks.acceptAll(course.getId(), "t@uni.kz").accepted()).isEqualTo(2);
        flushAndClear();

        assertThat(weekOf(notes)).containsExactly(3, null);
        assertThat(weekOf(slides)).containsExactly(9, null);
        assertThat(weekOf(stale)).as("past the course's weeks").containsExactly(null, 20);
        assertThat(weekOf(fixed)).containsExactly(1, null);
        // AuditService writes in a transaction of its own, so the row is committed already.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'DOCUMENT_WEEK_SET' AND details LIKE ?",
                Long.class, "Course " + course.getId() + ": 2 AI week suggestion(s) accepted%")).isEqualTo(1);
    }

    @Test
    void theWeeksCountCannotGoBelowAWeekInUse() {
        document("lecture-10.pdf", DocumentCategory.LECTURE, DocumentVisibility.COURSE_STUDENTS, 10, null);
        Document hinted = document("notes.pdf", DocumentCategory.OTHER, DocumentVisibility.COURSE_STUDENTS, null, 12);
        weeks.setTopic(course.getId(), 13, "Повторение", "t@uni.kz");
        flushAndClear();

        assertThatThrownBy(() -> weeks.updateWeeksCount(course.getId(), 8, "t@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.WEEK_IN_USE);
                });

        WeeksOverview overview = weeks.updateWeeksCount(course.getId(), 10, "t@uni.kz");
        flushAndClear();

        assertThat(overview.weeksCount()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT weeks_count FROM courses WHERE id = ?", Integer.class, course.getId()))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_weeks WHERE course_id = ?", Long.class,
                course.getId())).as("week 13's topic went with it").isZero();
        assertThat(weekOf(hinted)).containsExactly(null, null);
    }

    @Test
    void aSyllabusPlanKeepsTheTeachersTopicsRaisesTheWeeksAndSurvivesItsSyllabus() {
        weeks.setTopic(course.getId(), 2, "Тема преподавателя", "t@uni.kz");
        Document syllabus = document("syllabus.pdf", DocumentCategory.SYLLABUS, DocumentVisibility.COURSE_STUDENTS,
                null, null);
        syllabus.setMaskedText("Силлабус. Неделя 1 — введение. Неделя 2 — модель. Неделя 18 — проект. " + "x".repeat(60));
        Document lecture = document("Неделя 2 — слайды.pdf", DocumentCategory.LECTURE,
                DocumentVisibility.COURSE_STUDENTS, null, null);
        flushAndClear();
        when(ai.isConfigured()).thenReturn(true);
        when(ai.chatJson(anyString(), anyString())).thenReturn(Optional.of("""
                {"weeks": [{"week": 1, "topic": "Введение"}, {"week": 2, "topic": "Реляционная модель"},
                           {"week": 18, "topic": "Проект"}]}"""));

        plans.afterDocumentProcessed(syllabus.getId());
        flushAndClear();

        Map<Integer, String> topics = topics();
        assertThat(topics).containsEntry(1, "Введение").containsEntry(2, "Тема преподавателя").containsEntry(18, "Проект");
        assertThat(jdbc.queryForObject("SELECT weeks_count FROM courses WHERE id = ?", Integer.class, course.getId()))
                .isEqualTo(18);
        assertThat(weekOf(lecture)).as("hint from the file name").containsExactly(null, 2);

        jdbc.update("DELETE FROM documents WHERE id = ?", syllabus.getId());

        assertThat(topics()).containsEntry(1, "Введение").containsEntry(18, "Проект");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_weeks WHERE source_document_id IS NOT NULL",
                Long.class)).isZero();
    }

    private Map<Integer, String> topics() {
        Map<Integer, String> topics = new java.util.TreeMap<>();
        jdbc.query("SELECT week_number, topic FROM course_weeks WHERE course_id = ?",
                row -> {
                    topics.put(row.getInt(1), row.getString(2));
                }, course.getId());
        return topics;
    }

    private List<Integer> weekOf(Document document) {
        return jdbc.queryForObject("SELECT week_number, suggested_week FROM documents WHERE id = ?",
                (row, number) -> java.util.Arrays.asList((Integer) row.getObject(1), (Integer) row.getObject(2)),
                document.getId());
    }

    private static List<Long> listed(List<DocumentSummary> rows) {
        return rows.stream().map(DocumentSummary::id).toList();
    }

    private Document document(String name, DocumentCategory category, DocumentVisibility visibility, Integer week,
                              Integer suggested) {
        return persist(Document.builder().originalFileName(name).storedFileName(UUID.randomUUID().toString())
                .contentType("application/pdf").fileSize(1L).category(category).type(DocumentType.OTHER)
                .aiStatus(AiStatus.DONE).visibility(visibility).course(course).owner(teacher).maskedText("Текст")
                .weekNumber(week).suggestedWeek(suggested).suggestedWeekConfidence(suggested == null ? null : 0.9)
                .uploadedAt(LocalDateTime.now()).build());
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
