package com.university.docassistant.service;

import com.university.docassistant.config.DeadlineBeforeEnrollmentMigration;
import com.university.docassistant.config.JpaSettings;
import com.university.docassistant.dto.TaskDtos.TaskSummary;
import com.university.docassistant.dto.TaskResponse;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.Task;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskRepository.SubmitRules;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a student sees as "Просрочено" on real PostgreSQL: a CLOSED assignment's task is never overdue, and neither is
 * one whose deadline had already passed when the student enrolled (deadline_before_enrollment: set by fanOut, no
 * reminder, cleared when the deadline moves to the enrollment or later, backfilled once for old rows). The list
 * filter and the summary counters use the same condition. Opt-in like the other *PostgresTest classes; only ever the
 * separate doc_assistant_test database.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Import(JpaSettings.class)
class TaskOverduePostgresTest {

    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 20, 23, 59);
    private static final LocalDateTime NOW = DEADLINE.plusDays(3);
    private static final LocalDateTime ENROLLED_BEFORE = DEADLINE.minusDays(30);
    private static final LocalDateTime ENROLLED_AFTER = DEADLINE.plusDays(1);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("it.db.user", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty("it.db.password", ""));
    }

    @Autowired
    private TaskRepository tasks;
    @Autowired
    private EnrollmentRepository enrollments;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private Course course;
    private Assignment lab;

    @BeforeEach
    void setUp() {
        User teacher = persist(User.builder().email("t@uni.kz").fullName("Teacher").role(Role.ROLE_TEACHER)
                .createdAt(LocalDateTime.now()).build());
        course = persist(Course.builder().title("Algorithms").code("ALG").teacher(teacher).build());
        lab = persist(Assignment.builder().title("Lab 1").deadline(DEADLINE).status(AssignmentStatus.ACTIVE)
                .course(course).build());
    }

    @Test
    void aClosedAssignmentsTaskWithNothingHandedInIsNotOverdue() {
        User student = enrolledStudent(ENROLLED_BEFORE);
        handOut();
        assertThat(summary(student)).as("ACTIVE: overdue").isEqualTo(new TaskSummary(0, 1, 0));
        assertThat(ids(student, "OVERDUE")).hasSize(1);

        jdbc.update("UPDATE assignments SET status = 'CLOSED' WHERE id = ?", lab.getId());

        assertThat(summary(student)).as("CLOSED: neither overdue nor to do").isEqualTo(new TaskSummary(0, 0, 0));
        assertThat(ids(student, "OVERDUE")).isEmpty();
        assertThat(ids(student, "TO_DO")).isEmpty();
        assertThat(ids(student, "ALL")).as("still listed").hasSize(1);
    }

    @Test
    void aLateEnrolleeGetsTheFlagNoOverdueAndNoReminderButCanHandInLate() {
        User onTime = enrolledStudent(ENROLLED_BEFORE);
        User late = enrolledStudent(ENROLLED_AFTER);
        handOut();

        Task lateTask = taskOf(late);
        assertThat(lateTask.isDeadlineBeforeEnrollment()).isTrue();
        assertThat(taskOf(onTime).isDeadlineBeforeEnrollment()).isFalse();
        assertThat(tasks.findViewById(lateTask.getId())).hasValueSatisfying(
                view -> assertThat(view.deadlineBeforeEnrollment()).isTrue());

        assertThat(summary(late)).isEqualTo(new TaskSummary(0, 0, 0));
        assertThat(ids(late, "OVERDUE")).isEmpty();
        assertThat(summary(onTime)).as("enrolled in time: overdue").isEqualTo(new TaskSummary(0, 1, 0));

        // The 24-hour window around the deadline: only the student who was enrolled then is reminded.
        LocalDateTime dayBefore = DEADLINE.minusHours(12);
        assertThat(tasks.findReminderDueIds(0L, dayBefore, dayBefore.plusHours(24), PageRequest.of(0, 10)))
                .containsExactly(taskOf(onTime).getId());

        // With allow_late (the default) the hand-in is accepted and marked late as usual.
        SubmitRules rules = tasks.findSubmitRulesById(lateTask.getId()).orElseThrow();
        boolean markedLate = SubmissionService.checkAccepting(rules, NOW);
        assertThat(markedLate).isTrue();
        assertThat(tasks.markSubmitted(lateTask.getId(), NOW, markedLate)).isEqualTo(1);
        Task handedIn = taskOf(late);
        assertThat(handedIn.getStatus()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(handedIn.isLate()).isTrue();
    }

    @Test
    void movingTheDeadlineToAfterTheEnrollmentClearsTheFlag() {
        User late = enrolledStudent(ENROLLED_AFTER);
        handOut();

        moveDeadline(ENROLLED_AFTER.minusHours(1));                // Still before the enrollment: stays.
        assertThat(taskOf(late).isDeadlineBeforeEnrollment()).isTrue();

        moveDeadline(ENROLLED_AFTER.plusDays(2));                  // After it: the student had the time.
        assertThat(taskOf(late).isDeadlineBeforeEnrollment()).isFalse();

        moveDeadline(ENROLLED_AFTER.minusHours(1));                // Never set again by a deadline change.
        assertThat(taskOf(late).isDeadlineBeforeEnrollment()).isFalse();
        assertThat(summary(late)).as("now a real overdue").isEqualTo(new TaskSummary(0, 1, 0));
    }

    @Test
    void theMigrationFlagsOldRowsFromTheEnrollmentTimeOnce() {
        User onTime = enrolledStudent(ENROLLED_BEFORE);
        User late = enrolledStudent(ENROLLED_AFTER);
        User unknown = enrolledStudent(null);                      // No enrolled_at: stays false.
        handOut();
        jdbc.update("UPDATE tasks SET deadline_before_enrollment = false");   // As ddl-auto adds the column.

        migrate();
        assertThat(taskOf(late).isDeadlineBeforeEnrollment()).isTrue();
        assertThat(taskOf(onTime).isDeadlineBeforeEnrollment()).isFalse();
        assertThat(taskOf(unknown).isDeadlineBeforeEnrollment()).isFalse();

        // Cleared since (the teacher moved the deadline); a restart must not set it again.
        jdbc.update("UPDATE tasks SET deadline_before_enrollment = false WHERE id = ?", taskOf(late).getId());
        migrate();
        assertThat(taskOf(late).isDeadlineBeforeEnrollment()).isFalse();
    }

    private void migrate() {
        em.flush();
        new DeadlineBeforeEnrollmentMigration(jdbc).run(null);
        em.clear();
    }

    private User enrolledStudent(LocalDateTime enrolledAt) {
        User student = persist(User.builder().email(UUID.randomUUID() + "@uni.kz").fullName("Student")
                .role(Role.ROLE_STUDENT).createdAt(LocalDateTime.now()).build());
        enrollments.enroll(course.getId(), student.getId(), LocalDateTime.now());
        jdbc.update("UPDATE course_students SET enrolled_at = ? WHERE course_id = ? AND student_id = ?",
                enrolledAt, course.getId(), student.getId());
        return student;
    }

    private void handOut() {
        em.flush();
        tasks.fanOut(course.getId());
        em.clear();
    }

    private void moveDeadline(LocalDateTime deadline) {
        em.flush();
        jdbc.update("UPDATE assignments SET deadline = ? WHERE id = ?", deadline, lab.getId());
        tasks.syncWithAssignments(course.getId());
        em.clear();
    }

    private Task taskOf(User student) {
        em.clear();
        return em.createQuery("SELECT t FROM Task t WHERE t.user.id = :userId", Task.class)
                .setParameter("userId", student.getId()).getSingleResult();
    }

    private TaskSummary summary(User student) {
        return tasks.summarizeMine(student.getId(), NOW);
    }

    private List<Long> ids(User student, String state) {
        return tasks.findMyViews(student.getId(), state, null, NOW, PageRequest.of(0, 50)).getContent().stream()
                .map(TaskResponse::id).toList();
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }
}
