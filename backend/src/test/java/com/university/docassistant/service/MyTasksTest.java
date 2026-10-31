package com.university.docassistant.service;

import com.university.docassistant.dto.TaskDtos.TaskStateFilter;
import com.university.docassistant.dto.TaskDtos.TaskSummary;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskRepository.TaskAccess;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-12: "Мои задачи" lists only the current user's own tasks, filtered by state against the shared clock (the
 * conditions themselves are checked on PostgreSQL in MyTasksPostgresTest); another student's task by id is refused.
 */
class MyTasksTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 23, 59, 59);

    private final TaskRepository tasks = mock(TaskRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final AccessControlService access = new AccessControlService(users, mock(CourseRepository.class),
            mock(DocumentRepository.class), enrollments, mock(AuditService.class));
    private final TaskService service = new TaskService(tasks, access, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
    private final User student = User.builder().id(1L).email("s@uni.kz").role(Role.ROLE_STUDENT).build();

    @BeforeEach
    void setUp() {
        when(users.findByEmail("s@uni.kz")).thenReturn(Optional.of(student));
        when(tasks.findMyViews(anyLong(), any(), any(), any(), any())).thenReturn(Page.empty());
    }

    @Test
    void theListIsTheCurrentUsersOwnFilteredAtTheClocksNow() {
        service.listMine("s@uni.kz", TaskStateFilter.OVERDUE, 10L, PageRequest.of(0, 20));

        verify(tasks).findMyViews(eq(1L), eq("OVERDUE"), eq(10L), eq(NOW), any());
    }

    @Test
    void withoutFiltersEverythingOfAllCoursesIsListed() {
        service.listMine("s@uni.kz", null, null, PageRequest.of(0, 20));

        verify(tasks).findMyViews(eq(1L), eq("ALL"), isNull(), eq(NOW), any());
    }

    @Test
    void theSummaryCountsAtTheClocksNow() {
        when(tasks.summarizeMine(1L, NOW)).thenReturn(new TaskSummary(3, 1, 2));

        assertThat(service.summary("s@uni.kz")).isEqualTo(new TaskSummary(3, 1, 2));
    }

    @Test
    void anotherStudentsTaskByIdIsRefused() {
        when(tasks.findAccessById(9L)).thenReturn(Optional.of(new TaskAccess(9L, 99L, 10L, 3L)));

        assertThatThrownBy(() -> service.updateStatus(9L, TaskStatus.IN_PROGRESS, "s@uni.kz"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.TASK_NOT_OWN);
                });
        verify(tasks, never()).findViewById(any());
    }
}
