package com.university.docassistant.service;

import com.university.docassistant.config.JpaSettings;
import com.university.docassistant.dto.GradingDtos.GradingSummary;
import com.university.docassistant.dto.SubmissionDtos.SubmissionResponse;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.Task;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.TaskSubmission;
import com.university.docassistant.entity.User;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskRepository.GradingRow;
import com.university.docassistant.repository.TaskSubmissionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * US-11 on real PostgreSQL: the grading page's queries (rows of enrolled students by name, the state filters, the
 * progress counts, the current hand-in's files in one query) and grading / taking a grade back. Opt-in like the
 * other *PostgresTest classes; only ever the separate doc_assistant_test database.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Import(JpaSettings.class)
class GradingPostgresTest {

    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 20, 23, 59);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("it.db.user", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty("it.db.password", ""));
    }

    @Autowired
    private TaskRepository tasks;
    @Autowired
    private TaskSubmissionRepository submissions;
    @Autowired
    private EnrollmentRepository enrollments;
    @Autowired
    private EntityManager em;

    private User teacher;
    private Course course;
    private Assignment lab;
    private Task anna;      // On time, versions 1 and 2.
    private Task boris;     // Late.
    private Task vera;      // Nothing handed in.
    private Task left;      // Handed in, then left the course.

    @BeforeEach
    void setUp() {
        teacher = persist(User.builder().email("t@uni.kz").fullName("Teacher").role(Role.ROLE_TEACHER)
                .createdAt(LocalDateTime.now()).build());
        course = persist(Course.builder().title("Algorithms").code("ALG").teacher(teacher).build());
        lab = persist(Assignment.builder().title("Lab 1").deadline(DEADLINE).maxScore(10)
                .status(AssignmentStatus.ACTIVE).course(course).build());
        anna = task("Анна", TaskStatus.SUBMITTED, DEADLINE.minusDays(1), false);
        submit(anna, 1, "draft.pdf");
        submit(anna, 2, "final.pdf");
        submit(anna, 2, "appendix.pdf");
        boris = task("Борис", TaskStatus.SUBMITTED, DEADLINE.plusHours(5), true);
        submit(boris, 1, "late.pdf");
        vera = task("Вера", TaskStatus.IN_PROGRESS, null, false);
        task("Глеб", TaskStatus.GRADED, DEADLINE.minusHours(1), false);
        left = task("Яна", TaskStatus.SUBMITTED, DEADLINE.minusDays(2), false);
        enrollments.unenroll(course.getId(), left.getUser().getId());
        em.flush();
        em.clear();
    }

    @Test
    void theGradingPageListsEnrolledStudentsByNameWithTheirState() {
        assertThat(tasks.findGradingRows(lab.getId(), "ALL", PageRequest.of(0, 50)).getContent())
                .extracting(GradingRow::studentFullName)
                .as("by name; a student who left is not listed").containsExactly("Анна", "Борис", "Вера", "Глеб");
        assertThat(names("NOT_SUBMITTED")).containsExactly("Вера");
        assertThat(names("SUBMITTED")).containsExactly("Анна", "Борис");
        assertThat(names("LATE")).containsExactly("Борис");
        assertThat(names("GRADED")).containsExactly("Глеб");
        assertThat(tasks.findGradingRows(lab.getId(), "ALL", PageRequest.of(1, 3)).getContent())
                .extracting(GradingRow::studentFullName).as("paged").containsExactly("Глеб");
    }

    @Test
    void theProgressLineCountsTheSameStudents() {
        assertThat(tasks.summarizeGrading(lab.getId())).isEqualTo(new GradingSummary(4, 3, 1, 1));
    }

    @Test
    void onlyTheCurrentHandInsFilesComeBackInOneQuery() {
        assertThat(submissions.findCurrentViewsByTaskIdIn(List.of(anna.getId(), boris.getId(), vera.getId())))
                .extracting(SubmissionResponse::taskId, SubmissionResponse::originalFileName)
                .containsExactly(
                        tuple(anna.getId(), "final.pdf"),
                        tuple(anna.getId(), "appendix.pdf"),
                        tuple(boris.getId(), "late.pdf"));
    }

    @Test
    void gradingAndTakingTheGradeBack() {
        LocalDateTime now = DEADLINE.plusDays(2);
        tasks.grade(anna.getId(), new BigDecimal("8.5"), "Хорошо", now, em.find(User.class, teacher.getId()));
        em.clear();
        Task graded = em.find(Task.class, anna.getId());
        assertThat(graded.getStatus()).isEqualTo(TaskStatus.GRADED);
        assertThat(graded.getScore()).isEqualByComparingTo("8.5");
        assertThat(graded.getFeedback()).isEqualTo("Хорошо");
        assertThat(graded.getGradedAt()).isEqualTo(now);
        assertThat(graded.getGradedBy().getId()).isEqualTo(teacher.getId());
        assertThat(tasks.findGradeTargetById(anna.getId())).hasValueSatisfying(target -> {
            assertThat(target.maxScore()).isEqualTo(10);
            assertThat(target.score()).isEqualByComparingTo("8.5");
        });

        tasks.clearGrade(anna.getId(), TaskStatus.SUBMITTED);
        em.clear();
        Task back = em.find(Task.class, anna.getId());
        assertThat(back.getStatus()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(back.getScore()).isNull();
        assertThat(back.getFeedback()).isNull();
        assertThat(back.getGradedAt()).isNull();
        assertThat(back.getGradedBy()).isNull();
        assertThat(back.getSubmittedAt()).as("the hand-in stays").isEqualTo(DEADLINE.minusDays(1));
        assertThat(submissions.findViewsByTaskId(anna.getId())).hasSize(3);
    }

    private List<String> names(String filter) {
        return tasks.findGradingRows(lab.getId(), filter, PageRequest.of(0, 50)).getContent().stream()
                .map(GradingRow::studentFullName).toList();
    }

    private Task task(String name, TaskStatus status, LocalDateTime submittedAt, boolean late) {
        User student = persist(User.builder().email(UUID.randomUUID() + "@uni.kz").fullName(name)
                .role(Role.ROLE_STUDENT).createdAt(LocalDateTime.now()).build());
        enrollments.enroll(course.getId(), student.getId());
        return persist(Task.builder().title(lab.getTitle()).deadline(DEADLINE).status(status).course(course)
                .assignment(lab).user(student).submittedAt(submittedAt).late(late)
                .score(status == TaskStatus.GRADED ? BigDecimal.TEN : null).build());
    }

    private void submit(Task task, int version, String fileName) {
        persist(TaskSubmission.builder().task(task).student(task.getUser()).originalFileName(fileName)
                .storedFileName(UUID.randomUUID().toString().replace("-", "")).contentType("application/pdf")
                .fileSize(1L).submittedAt(DEADLINE.minusDays(3).plusHours(version)).version(version).build());
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }
}
