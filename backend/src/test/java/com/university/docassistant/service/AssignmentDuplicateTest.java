package com.university.docassistant.service;

import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.TaskExtractDto;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.AssignmentRepository.MatchInfo;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-08 step 3: a new AI draft that looks like an assignment already in its course is flagged (duplicateOf), never
 * merged. Only assignments of the same course count; different numbers are never duplicates.
 */
class AssignmentDuplicateTest {

    private static final LocalDateTime OCT_20 = LocalDateTime.of(2026, 10, 20, 18, 0);

    private final AssignmentRepository assignments = mock(AssignmentRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final Course course = Course.builder().id(10L).title("Algorithms").build();

    private AssignmentService service(boolean autoPublish) {
        return new AssignmentService(assignments, tasks, mock(CourseRepository.class), mock(DocumentRepository.class),
                access, mock(AuditService.class), mock(AssignmentEvents.class), autoPublish);
    }

    @BeforeEach
    void setUp() {
        when(assignments.getReferenceById(anyLong())).thenAnswer(invocation ->
                Assignment.builder().id(invocation.getArgument(0)).course(course).build());
    }

    /** The drafts the analysis created, after duplicate marking. */
    @SuppressWarnings("unchecked")
    private List<Assignment> analyze(String title, LocalDateTime deadline) {
        service(false).replaceFromDocument(50L, 10L, List.of(new TaskExtractDto(title, null, deadline)));
        ArgumentCaptor<List<Assignment>> saved = ArgumentCaptor.forClass(List.class);
        verify(assignments).saveAll(saved.capture());
        return saved.getValue();
    }

    private static MatchInfo existing(long id, String title, LocalDateTime deadline, AssignmentStatus status) {
        return new MatchInfo(id, title, deadline, status);
    }

    @Test
    void draftThatLooksLikeAnExistingAssignmentIsFlaggedNotMerged() {
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of(
                existing(5L, "Лабораторная работа №1", OCT_20.plusHours(5), AssignmentStatus.ACTIVE)));

        Assignment draft = analyze("Лаб. 1: сортировка", OCT_20).get(0);

        assertThat(draft.getStatus()).isEqualTo(AssignmentStatus.DRAFT);
        assertThat(draft.getDuplicateOf()).isNotNull();
        assertThat(draft.getDuplicateOf().getId()).isEqualTo(5L);
        verify(assignments, never()).deleteAllByIdInBatch(anyIterable()); // Nothing merged or removed.
    }

    @Test
    void sameNormalizedTitleIsFlaggedEvenWithoutDeadlines() {
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of(
                existing(5L, "СРС №2", null, AssignmentStatus.CLOSED)));

        assertThat(analyze("С.Р.С. 2", null).get(0).getDuplicateOf()).isNotNull();
    }

    @Test
    void differentNumbersAreNeverFlagged() {
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of(
                existing(5L, "Лаб. 2", OCT_20, AssignmentStatus.ACTIVE),
                existing(6L, "Лабораторная работа 12", OCT_20, AssignmentStatus.DRAFT)));

        assertThat(analyze("Лаб. 1", OCT_20).get(0).getDuplicateOf()).isNull();
    }

    @Test
    void sameKindAndNumberWithDeadlinesFarApartIsNotFlagged() {
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of(
                existing(5L, "Лабораторная работа 1 (повтор)", OCT_20.plusDays(14), AssignmentStatus.ACTIVE)));

        assertThat(analyze("Лаб. 1: сортировка", OCT_20).get(0).getDuplicateOf()).isNull();
    }

    @Test
    void markingDoesNotCrossCourses() {
        // The same lab exists, but in another course: only the draft's own course is ever compared.
        when(assignments.findMatchInfoByCourseId(11L)).thenReturn(List.of(
                existing(9L, "Лабораторная работа 1", OCT_20, AssignmentStatus.ACTIVE)));
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of());

        assertThat(analyze("Лабораторная работа 1", OCT_20).get(0).getDuplicateOf()).isNull();
        verify(assignments).findMatchInfoByCourseId(10L);
        verify(assignments, never()).findMatchInfoByCourseId(11L);
    }

    @Test
    void aPublishedOriginalIsPreferredOverAnotherDraft() {
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of(
                existing(3L, "Практика 3", OCT_20, AssignmentStatus.DRAFT),
                existing(7L, "Практическая работа №3", OCT_20, AssignmentStatus.ACTIVE)));

        assertThat(analyze("Пр. 3", OCT_20).get(0).getDuplicateOf().getId()).isEqualTo(7L);
    }

    @Test
    void autoPublishedAssignmentsAreNotFlagged() {
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of(
                existing(5L, "Лабораторная работа 1", OCT_20, AssignmentStatus.ACTIVE)));

        service(true).replaceFromDocument(50L, 10L, List.of(new TaskExtractDto("Лаб. 1", null, OCT_20)));

        verify(assignments, never()).findMatchInfoByCourseId(anyLong());
    }

    @Test
    void notADuplicateClearsTheFlag() {
        User teacher = User.builder().id(2L).email("teacher@uni.kz").role(Role.ROLE_TEACHER).build();
        when(access.currentUser("teacher@uni.kz")).thenReturn(teacher);
        when(access.authorizeCourse(teacher, 10L, Permission.MANAGE_ASSIGNMENTS)).thenReturn(course);
        Assignment draft = Assignment.builder().id(1L).title("Лаб. 1").status(AssignmentStatus.DRAFT).course(course)
                .duplicateOf(Assignment.builder().id(5L).build()).build();
        when(assignments.findById(1L)).thenReturn(Optional.of(draft));
        when(assignments.findViewById(1L)).thenReturn(Optional.of(new AssignmentResponse(1L, "Лаб. 1", null, null,
                AssignmentStatus.DRAFT, 10L, "Algorithms", null, null, null, null, null, null, null, false, true, null)));

        service(false).notDuplicate(1L, "teacher@uni.kz");

        assertThat(draft.getDuplicateOf()).isNull();
        verify(assignments).saveAndFlush(draft);
    }
}
