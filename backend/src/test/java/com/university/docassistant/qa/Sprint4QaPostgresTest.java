package com.university.docassistant.qa;

import com.university.docassistant.config.AsyncConfig;
import com.university.docassistant.config.JpaSettings;
import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.AssignmentDtos.CreateAssignmentRequest;
import com.university.docassistant.dto.AssignmentDtos.UpdateAssignmentRequest;
import com.university.docassistant.dto.GradingDtos.GradeRequest;
import com.university.docassistant.dto.SubmissionDtos.SubmissionResponse;
import com.university.docassistant.dto.TaskDtos.TaskStateFilter;
import com.university.docassistant.dto.TaskDtos.TaskSummary;
import com.university.docassistant.dto.TaskResponse;
import com.university.docassistant.entity.AuditLog;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.NotificationType;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.EmailSendException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AuditLogRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.NotificationRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.service.AssignmentEvents;
import com.university.docassistant.service.AssignmentService;
import com.university.docassistant.service.AuditService;
import com.university.docassistant.service.DeadlineReminderService;
import com.university.docassistant.service.DocumentPrivacyService;
import com.university.docassistant.service.EmailService;
import com.university.docassistant.service.GradingService;
import com.university.docassistant.service.NotificationEvents;
import com.university.docassistant.service.NotificationService;
import com.university.docassistant.service.SubmissionService;
import com.university.docassistant.service.TaskService;
import com.university.docassistant.service.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Sprint 4 acceptance cases (QA-SUB-010, QA-GRD-011, QA-UI-012, QA-NOT-013) end to end on PostgreSQL: the real
 * services, access control, queries and transactions, with real commits (no test transaction; the tables are
 * emptied after each case). Only the clock (fixed), the mail (a mock) and the file storage (a mock) are stand-ins,
 * and e-mails run on the test thread. Opt-in like the other *PostgresTest classes; only ever the separate
 * doc_assistant_test database (README "Тесты").
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnableConfigurationProperties(PrivacyProperties.class)
@Import({JpaSettings.class, Sprint4QaPostgresTest.Fixed.class, AccessControlService.class, AuditService.class,
        DocumentPrivacyService.class, AssignmentService.class, AssignmentEvents.class, NotificationService.class,
        NotificationEvents.class, SubmissionService.class, GradingService.class, TaskService.class,
        DeadlineReminderService.class})
class Sprint4QaPostgresTest {

    /** Saturday 24.10.2026 12:00 in the app time zone. */
    static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 12, 0);

    private static final String TEACHER = "o.kim@uni.kz";
    private static final String OTHER_TEACHER = "a.nur@uni.kz";
    private static final String STUDENT = "student@uni.kz";
    private static final String CLASSMATE = "classmate@uni.kz";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("it.db.user", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty("it.db.password", ""));
    }

    /** The fixed clock, e-mails on the test thread, and the transaction template SubmissionService uses. */
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
    }

    @MockBean
    private EmailService email;
    @MockBean
    private StorageService storage;

    @Autowired
    private AssignmentService assignments;
    @Autowired
    private SubmissionService submissions;
    @Autowired
    private GradingService grading;
    @Autowired
    private TaskService taskService;
    @Autowired
    private DeadlineReminderService reminders;
    @Autowired
    private UserRepository users;
    @Autowired
    private CourseRepository courses;
    @Autowired
    private EnrollmentRepository enrollments;
    @Autowired
    private TaskRepository tasks;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private AuditLogRepository auditLogs;
    @Autowired
    private TransactionTemplate transaction;
    @Autowired
    private JdbcTemplate jdbc;

    private User student;
    private User classmate;
    private Course course;
    private Course otherCourse;

    @BeforeEach
    void setUp() {
        when(storage.store(any(byte[].class))).thenAnswer(invocation -> UUID.randomUUID().toString().replace("-", ""));
        transaction.executeWithoutResult(status -> {
            User teacher = users.save(user(TEACHER, Role.ROLE_TEACHER));
            User other = users.save(user(OTHER_TEACHER, Role.ROLE_TEACHER));
            student = users.save(user(STUDENT, Role.ROLE_STUDENT));
            classmate = users.save(user(CLASSMATE, Role.ROLE_STUDENT));
            course = courses.save(Course.builder().title("Алгоритмы").code("INF345").teacher(teacher).build());
            otherCourse = courses.save(Course.builder().title("Базы данных").code("INF210").teacher(other).build());
            enrollments.enroll(course.getId(), student.getId());
            enrollments.enroll(course.getId(), classmate.getId());
        });
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE notifications, task_submissions, tasks, assignments, course_students, courses, "
                + "audit_log, users RESTART IDENTITY CASCADE");
    }

    @Nested
    @DisplayName("QA-SUB-010: submitting work")
    class QaSub010 {

        @Test
        @DisplayName("an on-time submission makes the task SUBMITTED, not late")
        void onTime() {
            Long task = taskOf(STUDENT, assignment("Лаб. 1", NOW.plusDays(2), true, null));

            submit(task, STUDENT);

            assertThat(tasks.findViewById(task)).hasValueSatisfying(view -> {
                assertThat(view.status()).isEqualTo(TaskStatus.SUBMITTED);
                assertThat(view.submittedAt()).isEqualTo(NOW);
                assertThat(view.late()).isFalse();
            });
        }

        @Test
        @DisplayName("after the deadline with late work allowed: accepted and marked late")
        void lateAllowed() {
            Long task = taskOf(STUDENT, assignment("Лаб. 2", NOW.minusHours(1), true, null));

            submit(task, STUDENT);

            assertThat(tasks.findViewById(task)).hasValueSatisfying(view -> {
                assertThat(view.status()).isEqualTo(TaskStatus.SUBMITTED);
                assertThat(view.late()).isTrue();
            });
        }

        @Test
        @DisplayName("after the deadline with late work forbidden: 400 naming the deadline, nothing stored")
        void lateForbidden() {
            Long task = taskOf(STUDENT, assignment("Лаб. 3", LocalDateTime.of(2026, 10, 23, 23, 59), false, null));

            assertThatThrownBy(() -> submit(task, STUDENT)).isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getCode()).isEqualTo(ErrorCodes.SUBMISSION_DEADLINE_PASSED);
                assertThat(ex.getMessage()).contains("23.10.2026 23:59");
            });
            assertThat(submissions.listForTask(task, STUDENT)).isEmpty();
            assertThat(tasks.findViewById(task)).hasValueSatisfying(view -> assertThat(view.status()).isEqualTo(TaskStatus.PENDING));
        }

        @Test
        @DisplayName("a resubmission is the current version, the earlier one stays as history")
        void resubmission() {
            Long task = taskOf(STUDENT, assignment("Лаб. 4", NOW.plusDays(2), true, null));

            submit(task, STUDENT, "draft.pdf");
            submit(task, STUDENT, "final.pdf");

            assertThat(submissions.listForTask(task, STUDENT))
                    .extracting(SubmissionResponse::originalFileName, SubmissionResponse::version, SubmissionResponse::current)
                    .containsExactly(tuple("final.pdf", 2, true), tuple("draft.pdf", 1, false));
        }

        @Test
        @DisplayName("a student cannot mark an assignment task done by hand")
        void noSelfComplete() {
            Long task = taskOf(STUDENT, assignment("Лаб. 5", NOW.plusDays(2), true, null));

            for (TaskStatus status : List.of(TaskStatus.COMPLETED, TaskStatus.SUBMITTED, TaskStatus.GRADED)) {
                assertCode(() -> taskService.updateStatus(task, status, STUDENT), HttpStatus.BAD_REQUEST,
                        ErrorCodes.TASK_STATUS_SYSTEM_ONLY);
            }
            assertThat(taskService.updateStatus(task, TaskStatus.IN_PROGRESS, STUDENT).status()).isEqualTo(TaskStatus.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("QA-GRD-011: grading and feedback")
    class QaGrd011 {

        @Test
        @DisplayName("8/10 with feedback: GRADED, and the student sees score and feedback")
        void gradeVisibleToTheStudent() {
            Long task = taskOf(STUDENT, assignment("Лаб. 1", NOW.plusDays(2), true, 10));
            submit(task, STUDENT);

            grading.grade(task, new GradeRequest(new BigDecimal("8"), "Хорошо, добавьте выводы"), TEACHER);

            TaskResponse mine = only(taskService.listMine(STUDENT, TaskStateFilter.GRADED, null, page()).content());
            assertThat(mine.id()).isEqualTo(task);
            assertThat(mine.status()).isEqualTo(TaskStatus.GRADED);
            assertThat(mine.score()).isEqualByComparingTo("8");
            assertThat(mine.maxScore()).isEqualTo(10);
            assertThat(mine.feedback()).isEqualTo("Хорошо, добавьте выводы");
            assertThat(mine.gradedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("12/10 is refused and nothing changes")
        void aboveTheMax() {
            Long task = taskOf(STUDENT, assignment("Лаб. 2", NOW.plusDays(2), true, 10));
            submit(task, STUDENT);

            assertCode(() -> grading.grade(task, new GradeRequest(new BigDecimal("12"), null), TEACHER),
                    HttpStatus.BAD_REQUEST, ErrorCodes.GRADE_SCORE_INVALID);
            assertThat(tasks.findViewById(task)).hasValueSatisfying(view -> assertThat(view.status()).isEqualTo(TaskStatus.SUBMITTED));
        }

        @Test
        @DisplayName("re-grading is audited with the old and the new score")
        void reGradeAudited() {
            Long task = taskOf(STUDENT, assignment("Лаб. 3", NOW.plusDays(2), true, 10));
            submit(task, STUDENT);

            grading.grade(task, new GradeRequest(new BigDecimal("8"), null), TEACHER);
            grading.grade(task, new GradeRequest(new BigDecimal("9.5"), null), TEACHER);

            assertThat(auditLogs.findAll()).filteredOn(log -> AuditService.TASK_GRADED.equals(log.getAction()))
                    .extracting(AuditLog::getDetails)
                    .anySatisfy(details -> assertThat(details).contains("— -> 8"))
                    .anySatisfy(details -> assertThat(details).contains("8 -> 9.5"));
        }

        @Test
        @DisplayName("work that was never handed in can be graded")
        void withoutSubmission() {
            Long task = taskOf(CLASSMATE, assignment("Лаб. 4", NOW.minusDays(1), true, 10));

            assertThat(grading.grade(task, new GradeRequest(BigDecimal.ZERO, "Не сдано"), TEACHER).status())
                    .isEqualTo(TaskStatus.GRADED);
        }

        @Test
        @DisplayName("another course's teacher gets 403, and so does a student")
        void otherTeacher() {
            Long task = taskOf(STUDENT, assignment("Лаб. 5", NOW.plusDays(2), true, 10));

            assertCode(() -> grading.grade(task, new GradeRequest(BigDecimal.TEN, null), OTHER_TEACHER),
                    HttpStatus.FORBIDDEN, ErrorCodes.COURSE_NOT_MANAGED);
            assertThatThrownBy(() -> grading.grade(task, new GradeRequest(BigDecimal.TEN, null), CLASSMATE))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThat(tasks.findViewById(task)).hasValueSatisfying(view -> assertThat(view.status()).isEqualTo(TaskStatus.PENDING));
        }
    }

    @Nested
    @DisplayName("QA-UI-012: \"Мои задачи\"")
    class QaUi012 {

        @Test
        @DisplayName("filters and counters, overdue strictly before now")
        void filtersAndCounters() {
            Long overdue = taskOf(STUDENT, assignment("Просрочено", NOW.minusSeconds(1), true, null));
            Long dueNow = taskOf(STUDENT, assignment("Ровно сейчас", NOW, true, null));
            Long tomorrow = taskOf(STUDENT, assignment("Завтра", NOW.plusDays(1), true, 10));
            Long handedIn = taskOf(STUDENT, assignment("Сдано", NOW.plusDays(3), true, 10));
            submit(handedIn, STUDENT);
            Long graded = taskOf(STUDENT, assignment("Оценено", NOW.plusDays(4), true, 10));
            submit(graded, STUDENT);
            grading.grade(graded, new GradeRequest(BigDecimal.TEN, null), TEACHER);

            assertThat(ids(TaskStateFilter.OVERDUE)).containsExactly(overdue);
            assertThat(ids(TaskStateFilter.TO_DO)).containsExactly(dueNow, tomorrow);
            assertThat(ids(TaskStateFilter.SUBMITTED)).containsExactly(handedIn);
            assertThat(ids(TaskStateFilter.GRADED)).containsExactly(graded);
            assertThat(ids(TaskStateFilter.ALL)).containsExactly(overdue, dueNow, tomorrow, handedIn, graded);
            assertThat(taskService.summary(STUDENT)).isEqualTo(new TaskSummary(2, 1, 1));
            assertThat(taskService.listMine(STUDENT, TaskStateFilter.ALL, otherCourse.getId(), page()).content())
                    .as("course filter").isEmpty();
        }

        @Test
        @DisplayName("another student's task is not reachable")
        void othersTask() {
            Long mine = taskOf(STUDENT, assignment("Лаб. 1", NOW.plusDays(2), true, null));
            submit(mine, STUDENT);

            assertThat(taskService.listMine(CLASSMATE, TaskStateFilter.ALL, null, page()).content())
                    .extracting(TaskResponse::id).doesNotContain(mine);
            assertCode(() -> taskService.updateStatus(mine, TaskStatus.IN_PROGRESS, CLASSMATE), HttpStatus.FORBIDDEN,
                    ErrorCodes.TASK_NOT_OWN);
            assertThatThrownBy(() -> submissions.listForTask(mine, CLASSMATE))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            assertCode(() -> submit(mine, CLASSMATE), HttpStatus.FORBIDDEN, ErrorCodes.TASK_NOT_OWN);
        }
    }

    @Nested
    @DisplayName("QA-NOT-013: notifications and reminders")
    class QaNot013 {

        @Test
        @DisplayName("the 24-hour reminder goes out once, never for submitted, closed or draft work")
        void reminders() {
            Long due = taskOf(STUDENT, assignment("Лаб. 1", NOW.plusHours(10), true, null));
            Long handedIn = taskOf(STUDENT, assignment("Лаб. 2", NOW.plusHours(10), true, null));
            submit(handedIn, STUDENT);
            AssignmentResponse closed = assignment("Лаб. 3", NOW.plusHours(10), true, null);
            assignments.close(closed.id(), TEACHER);
            // A draft is never handed out; even a task that somehow points at one gets no reminder.
            Long draft = jdbc.queryForObject("INSERT INTO assignments (title, deadline, status, course_id, allow_late, "
                    + "created_at) VALUES ('Черновик', ?, 'DRAFT', ?, true, now()) RETURNING id", Long.class,
                    NOW.plusHours(10), course.getId());
            jdbc.update("INSERT INTO tasks (title, deadline, status, course_id, user_id, assignment_id, late) "
                    + "VALUES ('Черновик', ?, 'PENDING', ?, ?, ?, false)", NOW.plusHours(10), course.getId(),
                    student.getId(), draft);

            // Лаб. 1 for both students, Лаб. 2 for the classmate only (the student handed it in).
            assertThat(reminders.sendDue()).isEqualTo(3);
            assertThat(reminders.sendDue()).as("once").isZero();
            assertThat(titlesOf(student, NotificationType.DEADLINE_REMINDER_24H))
                    .containsExactly("Срок сдачи «Лаб. 1» — сегодня в 22:00");
            assertThat(titlesOf(classmate, NotificationType.DEADLINE_REMINDER_24H))
                    .containsExactlyInAnyOrder("Срок сдачи «Лаб. 1» — сегодня в 22:00", "Срок сдачи «Лаб. 2» — сегодня в 22:00");
            assertThat(jdbc.queryForObject("SELECT reminder_sent_at FROM tasks WHERE id = ?", LocalDateTime.class, due))
                    .isEqualTo(NOW);
        }

        @Test
        @DisplayName("publishing and grading notify the students concerned")
        void publishAndGrade() {
            Long task = taskOf(STUDENT, assignment("Лаб. 1", NOW.plusDays(2), true, 10));
            submit(task, STUDENT);
            grading.grade(task, new GradeRequest(new BigDecimal("8"), null), TEACHER);

            assertThat(titlesOf(student, NotificationType.ASSIGNMENT_PUBLISHED)).containsExactly("Новое задание: Лаб. 1");
            assertThat(titlesOf(classmate, NotificationType.ASSIGNMENT_PUBLISHED)).containsExactly("Новое задание: Лаб. 1");
            assertThat(titlesOf(student, NotificationType.TASK_GRADED)).containsExactly("Работа оценена: Лаб. 1");
            assertThat(titlesOf(classmate, NotificationType.TASK_GRADED)).isEmpty();
        }

        @Test
        @DisplayName("a failing e-mail rolls back neither publishing nor grading")
        void mailFailure() {
            doThrow(new EmailSendException("smtp down", null)).when(email).sendNotification(any(), any(), any(), any(), any());

            Long task = taskOf(STUDENT, assignment("Лаб. 1", NOW.plusDays(2), true, 10));
            submit(task, STUDENT);
            grading.grade(task, new GradeRequest(new BigDecimal("7"), null), TEACHER);

            assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, task)).isEqualTo("GRADED");
            assertThat(notifications.countUnread(student.getId())).as("publication and grade").isEqualTo(2);
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    /** An ACTIVE assignment of INF345 created by its teacher (handed out to both students), with these rules. */
    private AssignmentResponse assignment(String title, LocalDateTime deadline, boolean allowLate, Integer maxScore) {
        AssignmentResponse created = assignments.create(course.getId(), new CreateAssignmentRequest(title, null, deadline), TEACHER);
        if (!allowLate || maxScore != null) {
            created = assignments.update(created.id(), new UpdateAssignmentRequest(title, null, deadline, allowLate, maxScore), TEACHER);
        }
        return created;
    }

    private Long taskOf(String studentEmail, AssignmentResponse assignment) {
        return jdbc.queryForObject("SELECT t.id FROM tasks t JOIN users u ON u.id = t.user_id "
                + "WHERE t.assignment_id = ? AND u.email = ?", Long.class, assignment.id(), studentEmail);
    }

    private void submit(Long task, String studentEmail) {
        submit(task, studentEmail, "solution.pdf");
    }

    private void submit(Long task, String studentEmail, String fileName) {
        List<MultipartFile> files = List.of(new MockMultipartFile("files", fileName, "application/pdf", new byte[]{1, 2, 3}));
        submissions.submit(task, files, studentEmail);
    }

    private List<Long> ids(TaskStateFilter state) {
        return taskService.listMine(STUDENT, state, null, page()).content().stream().map(TaskResponse::id).toList();
    }

    private List<String> titlesOf(User user, NotificationType type) {
        return notifications.findViewsByUserId(user.getId(), PageRequest.of(0, 50)).getContent().stream()
                .filter(row -> row.type() == type).map(row -> row.title()).toList();
    }

    private static PageRequest page() {
        return PageRequest.of(0, 50, Sort.by("deadline", "id"));
    }

    private static <T> T only(List<T> rows) {
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static void assertCode(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }

    private static User user(String email, Role role) {
        return User.builder().email(email).fullName(email).role(role).createdAt(LocalDateTime.now()).build();
    }
}
