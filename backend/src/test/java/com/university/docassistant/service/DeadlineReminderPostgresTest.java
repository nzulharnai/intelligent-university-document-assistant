package com.university.docassistant.service;

import com.university.docassistant.config.JpaSettings;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.NotificationType;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.Task;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * US-13 on real PostgreSQL with a fixed clock: a reminder goes out once per task, only for nothing-handed-in tasks of
 * ACTIVE assignments of enrolled students within the next 24 hours, and again after the deadline moved later.
 * Opt-in like the other *PostgresTest classes; only ever the separate doc_assistant_test database.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Import({JpaSettings.class, DeadlineReminderService.class, DeadlineReminderPostgresTest.FixedClock.class})
class DeadlineReminderPostgresTest {

    /** Saturday 24.10.2026 12:00 in the app time zone. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 12, 0);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("it.db.user", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty("it.db.password", ""));
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        }
    }

    @MockBean
    private NotificationService notifications;
    @MockBean
    private AuditService audit;

    @Autowired
    private DeadlineReminderService reminders;
    @Autowired
    private TaskRepository tasks;
    @Autowired
    private EnrollmentRepository enrollments;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private User student;
    private User leaver;
    private Course course;

    @BeforeEach
    void setUp() {
        User teacher = persist(user("t@uni.kz", Role.ROLE_TEACHER));
        student = persist(user("s@uni.kz", Role.ROLE_STUDENT));
        leaver = persist(user("left@uni.kz", Role.ROLE_STUDENT));
        course = persist(Course.builder().title("Алгоритмы").code("ALG").teacher(teacher).build());
        enrollments.enroll(course.getId(), student.getId());
    }

    @Test
    void aDueReminderIsSentOnceEvenWhenTheJobRunsAgain() {
        Task due = task("Lab 1", student, TaskStatus.IN_PROGRESS, LocalDateTime.of(2026, 10, 25, 9, 0), AssignmentStatus.ACTIVE);

        assertThat(reminders.sendDue()).isEqualTo(1);
        verify(notifications).notify(eq(List.of(student.getId())), eq(NotificationType.DEADLINE_REMINDER_24H),
                eq("Срок сдачи «Lab 1» — завтра в 09:00"), anyString(), eq("/dashboard"));
        assertThat(sentAt(due)).isEqualTo(NOW);

        clearInvocations(notifications);
        assertThat(reminders.sendDue()).as("a second run, or a second instance").isZero();
        verify(notifications, never()).notify(any(), any(), any(), any(), any());
        assertThat(tasks.claimReminder(due.getId(), NOW, NOW.plusHours(24))).as("the claim is taken").isZero();
    }

    @Test
    void onlyNothingHandedInTasksOfActiveAssignmentsOfEnrolledStudentsInTheNextDay() {
        LocalDateTime soon = NOW.plusHours(6);
        task("Сдано", student, TaskStatus.SUBMITTED, soon, AssignmentStatus.ACTIVE);
        task("Оценено", student, TaskStatus.GRADED, soon, AssignmentStatus.ACTIVE);
        task("Закрыто", student, TaskStatus.PENDING, soon, AssignmentStatus.CLOSED);
        task("Черновик", student, TaskStatus.PENDING, soon, AssignmentStatus.DRAFT);
        task("Ушёл с курса", leaver, TaskStatus.PENDING, soon, AssignmentStatus.ACTIVE);
        task("Через двое суток", student, TaskStatus.PENDING, NOW.plusHours(25), AssignmentStatus.ACTIVE);
        task("Уже прошло", student, TaskStatus.PENDING, NOW.minusMinutes(1), AssignmentStatus.ACTIVE);
        task("Сегодня", student, TaskStatus.PENDING, soon, AssignmentStatus.ACTIVE);

        assertThat(reminders.sendDue()).isEqualTo(1);
        verify(notifications).notify(eq(List.of(student.getId())), eq(NotificationType.DEADLINE_REMINDER_24H),
                eq("Срок сдачи «Сегодня» — сегодня в 18:00"), anyString(), anyString());
    }

    @Test
    void aDeadlineMovedLaterIsRemindedOfAgainButAnEarlierOneIsNot() {
        Task task = task("Lab 1", student, TaskStatus.PENDING, NOW.plusHours(3), AssignmentStatus.ACTIVE);
        assertThat(reminders.sendDue()).isEqualTo(1);

        moveDeadline(task, NOW.plusHours(2));        // Earlier: the reminder already said it is soon.
        assertThat(sentAt(task)).isEqualTo(NOW);
        assertThat(reminders.sendDue()).isZero();

        moveDeadline(task, NOW.plusHours(20));       // Later: the new deadline gets its own reminder.
        assertThat(sentAt(task)).isNull();
        assertThat(reminders.sendDue()).isEqualTo(1);
        verify(notifications, times(2)).notify(eq(List.of(student.getId())), eq(NotificationType.DEADLINE_REMINDER_24H),
                anyString(), anyString(), anyString());
    }

    @Test
    void largeRunsGoInBatches() {
        for (int i = 0; i < DeadlineReminderService.BATCH_SIZE + 5; i++) {
            task("Lab " + i, student, TaskStatus.PENDING, NOW.plusHours(5), AssignmentStatus.ACTIVE);
        }

        assertThat(reminders.sendDue()).isEqualTo(DeadlineReminderService.BATCH_SIZE + 5);
        assertThat(reminders.sendDue()).isZero();
    }

    @Test
    void theWordingFollowsTheDay() {
        assertThat(DeadlineReminderService.whenText(LocalDateTime.of(2026, 10, 24, 18, 0), NOW)).isEqualTo("сегодня в 18:00");
        assertThat(DeadlineReminderService.whenText(LocalDateTime.of(2026, 10, 25, 23, 59), NOW)).isEqualTo("завтра в 23:59");
        assertThat(DeadlineReminderService.whenText(LocalDateTime.of(2026, 10, 27, 9, 0), NOW)).isEqualTo("27.10.2026 в 09:00");
    }

    private void moveDeadline(Task task, LocalDateTime deadline) {
        em.flush();
        jdbc.update("UPDATE assignments SET deadline = ? WHERE id = (SELECT assignment_id FROM tasks WHERE id = ?)",
                deadline, task.getId());
        tasks.syncWithAssignments(course.getId());
        em.clear();
    }

    private LocalDateTime sentAt(Task task) {
        em.flush();
        em.clear();
        return em.find(Task.class, task.getId()).getReminderSentAt();
    }

    private Task task(String title, User owner, TaskStatus status, LocalDateTime deadline, AssignmentStatus assignmentStatus) {
        Assignment assignment = persist(Assignment.builder().title(title).deadline(deadline).status(assignmentStatus)
                .course(em.find(Course.class, course.getId())).build());
        return persist(Task.builder().title(title).deadline(deadline).status(status)
                .course(em.find(Course.class, course.getId())).assignment(assignment)
                .user(em.find(User.class, owner.getId())).build());
    }

    private static User user(String email, Role role) {
        return User.builder().email(email).fullName(email + UUID.randomUUID()).role(role).createdAt(LocalDateTime.now()).build();
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }
}
