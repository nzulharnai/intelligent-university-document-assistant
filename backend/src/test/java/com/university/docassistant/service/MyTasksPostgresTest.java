package com.university.docassistant.service;

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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-12 on real PostgreSQL: the state filters of "Мои задачи" (overdue strictly before the clock's now), the course
 * filter, the order by deadline with undated tasks last, the summary, and that only the user's own tasks come back.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@Import(JpaSettings.class)
class MyTasksPostgresTest {

    /** The clock's now in every query below. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 12, 0);
    private static final PageRequest BY_DEADLINE = PageRequest.of(0, 50, Sort.by("deadline", "id"));

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

    private User student;
    private Course algorithms;
    private Course databases;

    @BeforeEach
    void setUp() {
        User teacher = persist(User.builder().email("t@uni.kz").fullName("Teacher").role(Role.ROLE_TEACHER)
                .createdAt(LocalDateTime.now()).build());
        student = user("s@uni.kz");
        User other = user("o@uni.kz");
        algorithms = persist(Course.builder().title("Algorithms").code("ALG").teacher(teacher).build());
        databases = persist(Course.builder().title("Databases").code("DB").teacher(teacher).build());
        for (Course course : List.of(algorithms, databases)) {
            enrollments.enroll(course.getId(), student.getId());
            enrollments.enroll(course.getId(), other.getId());
        }
        task("Просрочено", algorithms, TaskStatus.PENDING, NOW.minusSeconds(1), false, AssignmentStatus.ACTIVE);
        task("В работе, просрочено", databases, TaskStatus.IN_PROGRESS, NOW.minusDays(2), false, AssignmentStatus.ACTIVE);
        task("Ровно сейчас", algorithms, TaskStatus.PENDING, NOW, false, AssignmentStatus.ACTIVE);
        task("Завтра", algorithms, TaskStatus.IN_PROGRESS, NOW.plusDays(1), false, AssignmentStatus.ACTIVE);
        task("Без срока", databases, TaskStatus.PENDING, null, false, AssignmentStatus.ACTIVE);
        task("Закрыто, не сдано", databases, TaskStatus.PENDING, NOW.plusDays(3), false, AssignmentStatus.CLOSED);
        task("Сдано", algorithms, TaskStatus.SUBMITTED, NOW.minusDays(3), false, AssignmentStatus.ACTIVE);
        task("Сдано поздно", algorithms, TaskStatus.SUBMITTED, NOW.minusDays(4), true, AssignmentStatus.ACTIVE);
        task("Оценено", databases, TaskStatus.GRADED, NOW.minusDays(5), false, AssignmentStatus.ACTIVE);
        persist(Task.builder().title("Старая задача").status(TaskStatus.PENDING).user(student).build());
        persist(Task.builder().title("Чужая").status(TaskStatus.PENDING).deadline(NOW.minusDays(1)).course(algorithms)
                .assignment(persist(Assignment.builder().title("Чужая").status(AssignmentStatus.ACTIVE).course(algorithms).build()))
                .user(other).build());
        em.flush();
        em.clear();
    }

    @Test
    void overdueMeansNothingHandedInAndTheDeadlineStrictlyBeforeNow() {
        assertThat(titles("OVERDUE", null)).containsExactly("В работе, просрочено", "Просрочено");
    }

    @Test
    void toDoIsStillOpenWorkNotYetOverdue() {
        assertThat(titles("TO_DO", null)).as("the deadline minute itself is still to do; closed and legacy aside")
                .containsExactly("Ровно сейчас", "Завтра", "Без срока", "Старая задача");
    }

    @Test
    void theOtherStateFilters() {
        assertThat(titles("NOT_STARTED", null)).containsExactly("Просрочено", "Ровно сейчас", "Закрыто, не сдано",
                "Без срока", "Старая задача");
        assertThat(titles("IN_PROGRESS", null)).containsExactly("В работе, просрочено", "Завтра");
        assertThat(titles("SUBMITTED", null)).containsExactly("Сдано поздно", "Сдано");
        assertThat(titles("LATE", null)).containsExactly("Сдано поздно");
        assertThat(titles("GRADED", null)).containsExactly("Оценено");
    }

    @Test
    void allIsSortedByDeadlineWithUndatedTasksLastAndOnlyTheStudentsOwn() {
        List<String> all = titles("ALL", null);
        assertThat(all).hasSize(10).doesNotContain("Чужая");
        assertThat(all.subList(0, 3)).containsExactly("Оценено", "Сдано поздно", "Сдано");
        assertThat(all.subList(8, 10)).containsExactlyInAnyOrder("Без срока", "Старая задача");
    }

    @Test
    void theCourseFilterCombinesWithTheState() {
        assertThat(titles("ALL", databases.getId())).containsExactly("Оценено", "В работе, просрочено",
                "Закрыто, не сдано", "Без срока");
        assertThat(titles("OVERDUE", algorithms.getId())).containsExactly("Просрочено");
    }

    @Test
    void theSummaryCountsTheSameTasks() {
        assertThat(tasks.summarizeMine(student.getId(), NOW)).isEqualTo(new TaskSummary(4, 2, 1));
    }

    private List<String> titles(String state, Long courseId) {
        return tasks.findMyViews(student.getId(), state, courseId, NOW, BY_DEADLINE).getContent().stream()
                .map(TaskResponse::title).toList();
    }

    private void task(String title, Course course, TaskStatus status, LocalDateTime deadline, boolean late,
                      AssignmentStatus assignmentStatus) {
        Assignment assignment = persist(Assignment.builder().title(title).deadline(deadline).status(assignmentStatus)
                .course(course).build());
        persist(Task.builder().title(title).deadline(deadline).status(status).late(late).course(course)
                .assignment(assignment).user(student)
                .submittedAt(status == TaskStatus.SUBMITTED || status == TaskStatus.GRADED ? deadline : null).build());
    }

    private User user(String email) {
        return persist(User.builder().email(email).fullName(email + UUID.randomUUID()).role(Role.ROLE_STUDENT)
                .createdAt(LocalDateTime.now()).build());
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }
}
