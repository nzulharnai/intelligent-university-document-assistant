package com.university.docassistant.service;

import com.university.docassistant.config.JpaSettings;
import com.university.docassistant.config.TaskSubmissionStatusMigration;
import com.university.docassistant.dto.SubmissionDtos.SubmissionResponse;
import com.university.docassistant.dto.TaskResponse;
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
import com.university.docassistant.repository.TaskRepository.SubmitRules;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * US-10 on real PostgreSQL: the startup migration of existing tasks to the submission lifecycle, and the queries
 * that keep it (markSubmitted, the student's conditional status change, late re-decided on a moved deadline, the
 * upcoming list). Opt-in like the other *PostgresTest classes; only ever the separate doc_assistant_test database.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Import(JpaSettings.class)
class TaskSubmissionStatusPostgresTest {

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
    @Autowired
    private JdbcTemplate jdbc;

    private User student;
    private Course course;
    private Assignment lab;
    private Assignment noDeadline;

    @BeforeEach
    void setUp() {
        User teacher = persist(User.builder().email("t@uni.kz").fullName("Teacher").role(Role.ROLE_TEACHER)
                .createdAt(LocalDateTime.now()).build());
        student = persist(User.builder().email("s@uni.kz").fullName("Student").role(Role.ROLE_STUDENT)
                .createdAt(LocalDateTime.now()).build());
        course = persist(Course.builder().title("Algorithms").code("ALG").teacher(teacher).build());
        enrollments.enroll(course.getId(), student.getId());
        lab = persist(Assignment.builder().title("Lab 1").deadline(DEADLINE).status(AssignmentStatus.ACTIVE)
                .course(course).build());
        noDeadline = persist(Assignment.builder().title("Essay").status(AssignmentStatus.ACTIVE).course(course).build());
    }

    @Test
    void migrationMovesExistingTasksToTheSubmissionLifecycleOnce() {
        Task lateWork = task(lab, TaskStatus.PENDING);
        submit(lateWork, DEADLINE.minusHours(1));
        submit(lateWork, DEADLINE.plusHours(2));                   // The latest one decides: late.
        Task onTime = task(lab, TaskStatus.COMPLETED);
        submit(onTime, DEADLINE.minusDays(1));
        Task undated = task(noDeadline, TaskStatus.IN_PROGRESS);
        submit(undated, DEADLINE.plusDays(5));                     // No deadline: never late.
        Task completedWithoutWork = task(lab, TaskStatus.COMPLETED);
        Task inProgress = task(lab, TaskStatus.IN_PROGRESS);
        Task untouched = task(lab, TaskStatus.PENDING);
        Task legacy = persist(Task.builder().title("Old task").status(TaskStatus.COMPLETED).user(student).build());
        // A database created before US-10: its status check knows only the three old values.
        jdbc.execute("ALTER TABLE tasks DROP CONSTRAINT IF EXISTS tasks_status_check");
        jdbc.execute("ALTER TABLE tasks ADD CONSTRAINT tasks_status_check "
                + "CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED'))");

        migrate();

        assertRow(lateWork, TaskStatus.SUBMITTED, DEADLINE.plusHours(2), true);
        assertRow(onTime, TaskStatus.SUBMITTED, DEADLINE.minusDays(1), false);
        assertRow(undated, TaskStatus.SUBMITTED, DEADLINE.plusDays(5), false);
        assertRow(completedWithoutWork, TaskStatus.IN_PROGRESS, null, false);
        assertRow(inProgress, TaskStatus.IN_PROGRESS, null, false);
        assertRow(untouched, TaskStatus.PENDING, null, false);
        assertRow(legacy, TaskStatus.COMPLETED, null, false);

        // Idempotent: a second run changes nothing, and never touches a task graded since.
        jdbc.update("UPDATE tasks SET status = 'GRADED' WHERE id = ?", onTime.getId());
        migrate();
        assertRow(lateWork, TaskStatus.SUBMITTED, DEADLINE.plusHours(2), true);
        assertRow(onTime, TaskStatus.GRADED, DEADLINE.minusDays(1), false);
        assertRow(completedWithoutWork, TaskStatus.IN_PROGRESS, null, false);
        assertRow(legacy, TaskStatus.COMPLETED, null, false);
    }

    @Test
    void aSubmissionMarksTheTaskSubmittedButNeverAGradedOne() {
        Task onTime = task(lab, TaskStatus.IN_PROGRESS);
        Task late = task(lab, TaskStatus.PENDING);
        Task graded = task(lab, TaskStatus.GRADED);

        assertThat(tasks.markSubmitted(onTime.getId(), DEADLINE.minusMinutes(1), false)).isEqualTo(1);
        assertThat(tasks.markSubmitted(late.getId(), DEADLINE.plusMinutes(1), true)).isEqualTo(1);
        assertThat(tasks.markSubmitted(graded.getId(), DEADLINE.minusMinutes(1), false)).isZero();

        assertRow(onTime, TaskStatus.SUBMITTED, DEADLINE.minusMinutes(1), false);
        assertRow(late, TaskStatus.SUBMITTED, DEADLINE.plusMinutes(1), true);
        assertRow(graded, TaskStatus.GRADED, null, false);
    }

    @Test
    void resubmissionKeepsTheHistoryAndMarksTheCurrentVersion() {
        Task task = task(lab, TaskStatus.PENDING);
        submit(task, DEADLINE.minusDays(2), 1);
        submit(task, DEADLINE.minusDays(2), 1);
        assertThat(submissions.findMaxVersion(task.getId())).isEqualTo(1);
        submit(task, DEADLINE.minusDays(1), 2);
        em.clear();

        assertThat(submissions.findMaxVersion(task.getId())).isEqualTo(2);
        assertThat(submissions.findViewsByTaskId(task.getId()))
                .extracting(SubmissionResponse::version, SubmissionResponse::current)
                .containsExactly(tuple(2, true), tuple(1, false), tuple(1, false));
        assertThat(submissions.findMaxVersion(task(lab, TaskStatus.PENDING).getId())).as("no hand-in yet").isZero();
    }

    @Test
    void theAcceptanceRulesComeWithTheAssignmentAndTheRowLockWorks() {
        lab.setAllowLate(false);
        em.merge(lab);
        Task task = task(lab, TaskStatus.SUBMITTED);
        em.flush();

        assertThat(tasks.lockById(task.getId())).contains(task.getId());
        assertThat(tasks.findSubmitRulesById(task.getId())).contains(
                new SubmitRules(TaskStatus.SUBMITTED, DEADLINE, false, AssignmentStatus.ACTIVE));
        Task legacy = persist(Task.builder().title("Old task").user(student).build());
        assertThat(tasks.findSubmitRulesById(legacy.getId())).as("no assignment, nothing to hand in to").isEmpty();
    }

    @Test
    void rowsFromBeforeVersionsAreVersionOne() {
        Task task = task(lab, TaskStatus.PENDING);
        submit(task, DEADLINE.minusDays(1), 1);
        // The column default fills rows inserted without a version, like rows that predate the column.
        jdbc.update("""
                INSERT INTO task_submissions (task_id, student_id, original_file_name, stored_file_name, content_type,
                                              file_size, submitted_at)
                VALUES (?, ?, 'old.pdf', ?, 'application/pdf', 1, ?)
                """, task.getId(), student.getId(), UUID.randomUUID().toString().replace("-", ""), DEADLINE.minusDays(3));

        assertThat(jdbc.queryForList("SELECT version FROM task_submissions WHERE task_id = ?", Integer.class,
                task.getId())).containsOnly(1);
    }

    @Test
    void theStudentsOwnChangeNeverOverwritesASubmission() {
        Task working = task(lab, TaskStatus.PENDING);
        Task submitted = task(lab, TaskStatus.SUBMITTED);

        assertThat(tasks.updateStudentStatus(working.getId(), TaskStatus.IN_PROGRESS)).isEqualTo(1);
        assertThat(tasks.updateStudentStatus(submitted.getId(), TaskStatus.PENDING)).isZero();

        assertRow(working, TaskStatus.IN_PROGRESS, null, false);
        assertRow(submitted, TaskStatus.SUBMITTED, null, false);
    }

    @Test
    void movingTheDeadlineRedecidesLateness() {
        Task task = task(lab, TaskStatus.PENDING);
        tasks.markSubmitted(task.getId(), DEADLINE.plusHours(3), true);
        assertRow(task, TaskStatus.SUBMITTED, DEADLINE.plusHours(3), true);

        jdbc.update("UPDATE assignments SET deadline = ? WHERE id = ?", DEADLINE.plusDays(1), lab.getId());
        tasks.syncWithAssignments(course.getId());
        assertRow(task, TaskStatus.SUBMITTED, DEADLINE.plusHours(3), false);   // The extension forgives it.

        jdbc.update("UPDATE assignments SET deadline = ? WHERE id = ?", DEADLINE, lab.getId());
        tasks.syncWithAssignments(course.getId());
        assertRow(task, TaskStatus.SUBMITTED, DEADLINE.plusHours(3), true);
    }

    @Test
    void theStudentsViewCarriesTheLifecycleAndUpcomingSkipsHandedInWork() {
        lab.setMaxScore(100);
        em.merge(lab);
        Task submitted = task(lab, TaskStatus.PENDING);
        tasks.markSubmitted(submitted.getId(), DEADLINE.plusHours(1), true);
        Task open = task(noDeadline, TaskStatus.IN_PROGRESS);
        jdbc.update("UPDATE assignments SET deadline = ? WHERE id = ?", DEADLINE.plusDays(3), noDeadline.getId());
        tasks.syncWithAssignments(course.getId());
        em.clear();

        TaskResponse view = tasks.findViewById(submitted.getId()).orElseThrow();
        assertThat(view.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(view.submittedAt()).isEqualTo(DEADLINE.plusHours(1));
        assertThat(view.late()).isTrue();
        assertThat(view.maxScore()).isEqualTo(100);
        assertThat(view.allowLate()).isTrue();
        assertThat(view.score()).isNull();

        assertThat(tasks.findUpcomingViews(student.getId(), DEADLINE.minusDays(1), PageRequest.of(0, 10)).getContent())
                .extracting(TaskResponse::id).containsExactly(open.getId());
    }

    private void migrate() {
        em.flush();
        new TaskSubmissionStatusMigration(jdbc).run(null);
        em.clear();
    }

    private void assertRow(Task task, TaskStatus status, LocalDateTime submittedAt, boolean late) {
        em.flush();
        em.clear();
        Task row = em.find(Task.class, task.getId());
        assertThat(row.getStatus()).as("status of %s", task.getTitle()).isEqualTo(status);
        assertThat(row.getSubmittedAt()).as("submitted_at of %s", task.getTitle()).isEqualTo(submittedAt);
        assertThat(row.isLate()).as("late of %s", task.getTitle()).isEqualTo(late);
    }

    private Task task(Assignment assignment, TaskStatus status) {
        return persist(Task.builder().title(assignment.getTitle() + " " + UUID.randomUUID()).status(status)
                .deadline(assignment.getDeadline()).course(course).assignment(assignment)
                .user(em.find(User.class, student.getId())).build());
    }

    private void submit(Task task, LocalDateTime at) {
        submit(task, at, 1);
    }

    private void submit(Task task, LocalDateTime at, int version) {
        persist(TaskSubmission.builder().task(em.find(Task.class, task.getId())).version(version)
                .student(em.find(User.class, student.getId())).originalFileName("solution.pdf")
                .storedFileName(UUID.randomUUID().toString().replace("-", "")).contentType("application/pdf")
                .fileSize(1L).submittedAt(at).build());
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }
}
