package com.university.docassistant.service;

import com.university.docassistant.config.AsyncConfig;
import com.university.docassistant.config.JpaSettings;
import com.university.docassistant.dto.NotificationDtos.NotificationResponse;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.NotificationType;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.EmailSendException;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.NotificationRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * US-13 on real PostgreSQL with real commits (no test transaction): a notified action commits with its
 * notifications even when every e-mail fails; only users who kept e-mails on are mailed; every query is limited to
 * the user's own notifications. Opt-in like the other *PostgresTest classes; only the separate doc_assistant_test
 * database, emptied after each test.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaSettings.class, NotificationService.class, AssignmentEvents.class, AccessControlService.class,
        AuditService.class, NotificationPostgresTest.MailOnTheTestThread.class})
class NotificationPostgresTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("it.db.user", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty("it.db.password", ""));
    }

    /** E-mails on the calling thread, so the test sees them; the clock of the app. */
    @TestConfiguration
    static class MailOnTheTestThread {
        @Bean(name = AsyncConfig.MAIL_EXECUTOR)
        TaskExecutor mailTaskExecutor() {
            return new SyncTaskExecutor();
        }

        @Bean
        Clock clock() {
            return Clock.systemDefaultZone();
        }
    }

    @MockBean
    private EmailService email;

    @Autowired
    private AssignmentEvents events;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private UserRepository users;
    @Autowired
    private CourseRepository courses;
    @Autowired
    private EnrollmentRepository enrollments;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private User wantsMail;
    private User noMail;
    private Course course;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            User teacher = users.save(user("t@uni.kz", Role.ROLE_TEACHER, true));
            wantsMail = users.save(user("mail@uni.kz", Role.ROLE_STUDENT, true));
            noMail = users.save(user("quiet@uni.kz", Role.ROLE_STUDENT, false));
            course = courses.save(Course.builder().title("Алгоритмы").code("ALG").teacher(teacher).build());
            enrollments.enroll(course.getId(), wantsMail.getId());
            enrollments.enroll(course.getId(), noMail.getId());
        });
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE notifications, course_students, courses, users RESTART IDENTITY CASCADE");
    }

    @Test
    void theActionCommitsWithItsNotificationsEvenWhenEveryEmailFails() {
        doThrow(new EmailSendException("smtp down", null)).when(email).sendNotification(any(), any(), any(), any(), any());

        assertThatNoException().isThrownBy(() -> transaction.executeWithoutResult(status ->
                events.published(course.getId(), course.getTitle(), List.of("Lab 1"))));

        assertThat(notifications.countUnread(wantsMail.getId())).isEqualTo(1);
        assertThat(notifications.countUnread(noMail.getId())).as("the bell shows it without e-mails too").isEqualTo(1);
        verify(email).sendNotification(eq("mail@uni.kz"), any(), eq("Новое задание: Lab 1"), any(), any());
        verify(email, never()).sendNotification(eq("quiet@uni.kz"), any(), any(), any(), any());
    }

    @Test
    void nothingIsMailedWhenTheActionRollsBack() {
        try {
            transaction.executeWithoutResult(status -> {
                events.published(course.getId(), course.getTitle(), List.of("Lab 1"));
                status.setRollbackOnly();
            });
        } finally {
            assertThat(notifications.countUnread(wantsMail.getId())).isZero();
            verify(email, never()).sendNotification(any(), any(), any(), any(), any());
        }
    }

    @Test
    void everyQueryIsLimitedToTheUsersOwnNotifications() {
        transaction.executeWithoutResult(status ->
                events.published(course.getId(), course.getTitle(), List.of("Lab 1", "Lab 2")));
        Long mine = notifications.findViewsByUserId(wantsMail.getId(), PageRequest.of(0, 10)).getContent().get(0).id();

        assertThat(notifications.findViewByIdAndUserId(mine, noMail.getId())).isEmpty();
        assertThat(updated(() -> notifications.markRead(mine, noMail.getId(), LocalDateTime.now()))).isZero();
        assertThat(notifications.countUnread(wantsMail.getId())).isEqualTo(1);

        assertThat(updated(() -> notifications.markRead(mine, wantsMail.getId(), LocalDateTime.now()))).isEqualTo(1);
        assertThat(notifications.findViewByIdAndUserId(mine, wantsMail.getId()))
                .hasValueSatisfying(row -> assertThat(row.readAt()).isNotNull());
        assertThat(updated(() -> notifications.markAllRead(noMail.getId(), LocalDateTime.now()))).isEqualTo(1);
        assertThat(notifications.countUnread(noMail.getId())).isZero();
        List<NotificationResponse> page = notifications.findViewsByUserId(noMail.getId(), PageRequest.of(0, 10)).getContent();
        assertThat(page).singleElement().satisfies(row -> {
            assertThat(row.type()).isEqualTo(NotificationType.ASSIGNMENT_PUBLISHED);
            assertThat(row.title()).isEqualTo("Новые задания: 2");
        });
    }

    /** Runs one update in its own committed transaction and returns its row count. */
    private int updated(IntSupplier update) {
        Integer rows = transaction.execute(status -> update.getAsInt());
        return rows == null ? 0 : rows;
    }

    private static User user(String email, Role role, boolean emailNotifications) {
        return User.builder().email(email).fullName(email).role(role).emailNotifications(emailNotifications)
                .createdAt(LocalDateTime.now()).build();
    }
}
