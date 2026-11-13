package com.university.docassistant.service;

import com.university.docassistant.config.AiProperties;
import com.university.docassistant.config.DocumentSearchSchema;
import com.university.docassistant.config.JpaSettings;
import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.DocumentAnswerResponse;
import com.university.docassistant.dto.SourceSnippetDto;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentChunk;
import com.university.docassistant.entity.DocumentType;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.AssignmentRepository.QnaAssignment;
import com.university.docassistant.repository.DocumentChunkRepository;
import com.university.docassistant.repository.DocumentChunkRepository.ChunkHit;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.AccessControlService.Scope;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-14 retrieval on PostgreSQL: the native chunk and assignment queries behind DocumentQnaService, with the real
 * russian full-text configuration. The AI and the access checks are mocked; the scope they return is what
 * AccessControlService.scope gives a student. Opt-in like every *PostgresTest (README "Тесты"), only ever against
 * doc_assistant_test.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Import({JpaSettings.class, DocumentQnaService.class, DocumentPrivacyService.class, DocumentQnaPostgresTest.Beans.class})
@EnableConfigurationProperties(PrivacyProperties.class)
class DocumentQnaPostgresTest {

    static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 12, 0);
    private static final String STUDENT = "s@uni.kz";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("it.db.user", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty("it.db.password", ""));
    }

    @TestConfiguration
    static class Beans {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        }

        @Bean
        AiProperties aiProperties() {
            return new AiProperties(true, "key", "m", "http://x", Duration.ofSeconds(1), Duration.ofSeconds(1),
                    1000, 1, 1, 2, 1);
        }
    }

    @MockBean
    private AccessControlService access;
    @MockBean
    private AiClientService ai;
    @MockBean
    private DocumentService documentService;

    @Autowired
    private DocumentQnaService service;
    @Autowired
    private DocumentChunkRepository chunks;
    @Autowired
    private AssignmentRepository assignments;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private User student;
    private Course course;
    private Course otherCourse;

    @BeforeEach
    void setUp() {
        new DocumentSearchSchema(jdbc).run(null);
        User teacher = persist(User.builder().email("t@uni.kz").fullName("Teacher").role(Role.ROLE_TEACHER)
                .createdAt(LocalDateTime.now()).build());
        student = persist(User.builder().email(STUDENT).fullName("Student").role(Role.ROLE_STUDENT)
                .createdAt(LocalDateTime.now()).build());
        course = persist(Course.builder().title("Базы данных").code("DB").teacher(teacher).build());
        otherCourse = persist(Course.builder().title("Чужой курс").code("XX").teacher(teacher).build());
        when(access.currentUser(STUDENT)).thenReturn(student);
        when(access.scope(student)).thenReturn(new Scope(false, List.of(course.getId()), false));
        when(ai.isConfigured()).thenReturn(true);
    }

    @Test
    void theNumberOfTheLabRanksItsFragmentFirst() {
        Document labs = document(course, "labs.pdf", DocumentVisibility.COURSE_STUDENTS);
        chunk(labs, 0, "Лабораторная работа 1 сдаётся до 10 декабря.");
        chunk(labs, 1, "Лабораторная работа 2 сдаётся до 20 декабря.");
        flushAndClear();

        List<ChunkHit> withNumber = chunks.searchInCourses(List.of(course.getId()), false,
                DocumentQnaService.toTsQuery("Когда сдавать лабораторную 2?").orElseThrow(), 8);

        assertThat(withNumber).extracting(ChunkHit::getChunkIndex).containsExactly(1, 0);
        assertThat(withNumber.get(0).getCourseTitle()).isEqualTo("Базы данных");
        assertThat(withNumber.get(0).getCourseId()).isEqualTo(course.getId());
    }

    @Test
    void studentQuestionNeverGetsTeacherOnlyOrOtherCourses() {
        Document lecture = document(course, "lecture.pdf", DocumentVisibility.COURSE_STUDENTS);
        Document key = document(course, "exam-key.pdf", DocumentVisibility.TEACHER_ONLY);
        Document foreign = document(otherCourse, "foreign.pdf", DocumentVisibility.COURSE_STUDENTS);
        chunk(lecture, 0, "Экзамен по базам данных пройдёт в декабре.");
        chunk(key, 0, "Ответы к экзамену: вариант А.");
        chunk(foreign, 0, "Экзамен чужого курса в январе.");
        assignment(course, "Экзамен", NOW.plusDays(30), AssignmentStatus.ACTIVE);
        assignment(otherCourse, "Экзамен чужого курса", NOW.plusDays(40), AssignmentStatus.ACTIVE);
        flushAndClear();
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("В декабре [lecture.pdf, фрагмент 1]."));

        DocumentAnswerResponse response = service.askAll("Когда экзамен?", STUDENT);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(ai).chat(system.capture(), anyString());
        assertThat(system.getValue()).contains("Экзамен по базам данных пройдёт в декабре.",
                        "Курс: Базы данных · Название: Экзамен")
                .doesNotContain("Ответы к экзамену", "чужого курса", "Чужой курс");
        assertThat(response.sources()).extracting(SourceSnippetDto::documentId, SourceSnippetDto::courseTitle)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(lecture.getId(), "Базы данных"));
    }

    @Test
    void assignmentsMatchingTheQuestionComeFirstThenUpcomingDeadlines() {
        Assignment lab2 = assignment(course, "Лабораторная работа 2", NOW.plusDays(20), AssignmentStatus.ACTIVE);
        Assignment soon = assignment(course, "Эссе", NOW.plusDays(1), AssignmentStatus.ACTIVE);
        Assignment later = assignment(course, "Проект", NOW.plusDays(5), AssignmentStatus.ACTIVE);
        Assignment noDeadline = assignment(course, "Реферат", null, AssignmentStatus.ACTIVE);
        Assignment past = assignment(course, "Доклад", NOW.minusDays(3), AssignmentStatus.ACTIVE);
        assignment(course, "Лабораторная работа 2 (старая)", NOW.plusDays(2), AssignmentStatus.CLOSED);
        assignment(otherCourse, "Лабораторная работа 2", NOW.plusDays(2), AssignmentStatus.ACTIVE);
        flushAndClear();

        List<QnaAssignment> found = assignments.findForQnaInCourses(List.of(course.getId()),
                DocumentQnaService.toTsQuery("Когда сдавать лабораторную 2?").orElseThrow(), NOW, 30);

        assertThat(found).extracting(QnaAssignment::getId).containsExactly(lab2.getId(), soon.getId(),
                later.getId(), noDeadline.getId(), past.getId());
        assertThat(found.get(0).getCourseTitle()).isEqualTo("Базы данных");
        assertThat(assignments.findForQnaInCourses(List.of(course.getId()), "", NOW, 2))
                .extracting(QnaAssignment::getId).containsExactly(soon.getId(), later.getId());
    }

    @Test
    void questionWithoutMatchingFragmentsStillAnswersFromTheMovedDeadline() {
        Document syllabus = document(course, "syllabus.pdf", DocumentVisibility.COURSE_STUDENTS);
        chunk(syllabus, 0, "Курсовой проект сдаётся до 1 декабря.");
        Assignment project = assignment(course, "Курсовой проект", LocalDateTime.of(2026, 12, 15, 18, 0),
                AssignmentStatus.ACTIVE);
        flushAndClear();
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of(
                "Срок изменён: до 15.12.2026 18:00 [задание 1]."));

        DocumentAnswerResponse response = service.askCourse(course.getId(), "Что с курсовым?", STUDENT);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(ai).chat(system.capture(), anyString());
        assertThat(system.getValue()).contains("Срок: 15.12.2026 18:00");
        assertThat(response.assignments()).extracting(a -> a.assignmentId()).containsExactly(project.getId());
        assertThat(response.citedExplicitly()).isTrue();
    }

    private Document document(Course owner, String name, DocumentVisibility visibility) {
        return persist(Document.builder().originalFileName(name).storedFileName(UUID.randomUUID().toString())
                .contentType("application/pdf").fileSize(1L).category(DocumentCategory.LECTURE)
                .type(DocumentType.OTHER).aiStatus(AiStatus.DONE).visibility(visibility).course(owner)
                .owner(owner.getTeacher()).maskedText("text").uploadedAt(LocalDateTime.now()).build());
    }

    private void chunk(Document document, int index, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(document);
        chunk.setChunkIndex(index);
        chunk.setStartOffset(index * 100);
        chunk.setEndOffset(index * 100 + content.length());
        chunk.setContent(content);
        persist(chunk);
    }

    private Assignment assignment(Course owner, String title, LocalDateTime deadline, AssignmentStatus status) {
        return persist(Assignment.builder().title(title).deadline(deadline).status(status).course(owner).build());
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
