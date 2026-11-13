package com.university.docassistant.service;

import com.university.docassistant.config.JpaSettings;
import com.university.docassistant.dto.GradingDtos.GradeRequest;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.Task;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskSubmissionRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Grading a task with nothing handed in must not lock the student out before the deadline. Runs the real
 * GradingService on PostgreSQL, so the check reads the assignment's deadline and status under the task's row lock.
 * Opt-in like the other *PostgresTest classes; only ever the separate doc_assistant_test database.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Import(JpaSettings.class)
class GradingDeadlinePostgresTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 18, 0);

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
    private AssignmentRepository assignments;
    @Autowired
    private EntityManager em;

    private final AccessControlService access = mock(AccessControlService.class);
    private GradingService service;
    private User teacher;
    private Course course;

    @BeforeEach
    void setUp() {
        teacher = persist(User.builder().email("t@uni.kz").fullName("Teacher").role(Role.ROLE_TEACHER)
                .createdAt(LocalDateTime.now()).build());
        course = persist(Course.builder().title("Algorithms").code("ALG").teacher(teacher).build());
        when(access.currentUser("t@uni.kz")).thenReturn(teacher);
        when(access.authorizeCourse(eq(teacher), anyLong(), eq(Permission.GRADE_WORK))).thenReturn(course);
        service = new GradingService(tasks, submissions, assignments, access, mock(AuditService.class),
                mock(NotificationEvents.class), Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
    }

    @Test
    void nothingHandedInBeforeTheDeadlineIsRefusedAndTheStudentCanStillHandIn() {
        Task task = task(NOW.plusDays(2), AssignmentStatus.ACTIVE, null);

        assertThatThrownBy(() -> grade(task))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.GRADE_TOO_EARLY);
                    assertThat(ex.getMessage()).isEqualTo("Студент ещё может сдать работу до 26.10.2026 18:00");
                });
        em.clear();
        assertThat(em.find(Task.class, task.getId()).getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void nothingHandedInAfterTheDeadlineIsGraded() {
        assertGraded(task(NOW.minusHours(1), AssignmentStatus.ACTIVE, null));
    }

    @Test
    void nothingHandedInWithoutADeadlineIsGraded() {
        assertGraded(task(null, AssignmentStatus.ACTIVE, null));
    }

    @Test
    void handedInWorkBeforeTheDeadlineIsGraded() {
        assertGraded(task(NOW.plusDays(2), AssignmentStatus.ACTIVE, NOW.minusHours(3)));
    }

    @Test
    void nothingHandedInForAClosedAssignmentIsGradedBeforeItsDeadline() {
        assertGraded(task(NOW.plusDays(2), AssignmentStatus.CLOSED, null));
    }

    private void assertGraded(Task task) {
        grade(task);
        em.clear();
        Task graded = em.find(Task.class, task.getId());
        assertThat(graded.getStatus()).isEqualTo(TaskStatus.GRADED);
        assertThat(graded.getScore()).isEqualByComparingTo("5");
        assertThat(graded.getGradedAt()).isEqualTo(NOW);
    }

    private void grade(Task task) {
        service.grade(task.getId(), new GradeRequest(new BigDecimal("5"), null), "t@uni.kz");
    }

    /** One enrolled student's task of a new assignment with {@code deadline} and {@code status}. */
    private Task task(LocalDateTime deadline, AssignmentStatus status, LocalDateTime submittedAt) {
        Assignment lab = persist(Assignment.builder().title("Lab " + UUID.randomUUID()).deadline(deadline)
                .maxScore(10).status(status).course(course).build());
        User student = persist(User.builder().email(UUID.randomUUID() + "@uni.kz").fullName("Student")
                .role(Role.ROLE_STUDENT).createdAt(LocalDateTime.now()).build());
        Task task = persist(Task.builder().title(lab.getTitle()).deadline(deadline)
                .status(submittedAt == null ? TaskStatus.PENDING : TaskStatus.SUBMITTED).course(course)
                .assignment(lab).user(student).submittedAt(submittedAt).build());
        em.clear();
        return task;
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }
}
