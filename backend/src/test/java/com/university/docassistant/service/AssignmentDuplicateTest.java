package com.university.docassistant.service;

import com.university.docassistant.dto.TaskExtractDto;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.AssignmentRepository.MatchInfo;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.security.AccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI assignments are published at once (the draft step was removed at the course teacher's request): created ACTIVE,
 * handed out and announced, the publication audited. An AI item the course already has - same normalized title and a
 * deadline on the same day (AssignmentTitles.sameAssignment) - is skipped; different numbers are never duplicates,
 * and only the document's own course is compared.
 */
class AssignmentDuplicateTest {

    private static final LocalDateTime OCT_20 = LocalDateTime.of(2026, 10, 20, 18, 0);

    private final AssignmentRepository assignments = mock(AssignmentRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final AssignmentEvents events = mock(AssignmentEvents.class);
    private final AssignmentService service = new AssignmentService(assignments, tasks, courses, documents,
            mock(AccessControlService.class), audit, events);
    private final Course course = Course.builder().id(10L).title("Algorithms").build();

    @BeforeEach
    void setUp() {
        when(courses.getReferenceById(10L)).thenReturn(course);
        when(courses.findById(10L)).thenReturn(Optional.of(course));
        when(documents.getReferenceById(anyLong())).thenAnswer(invocation ->
                Document.builder().id(invocation.getArgument(0)).build());
        when(assignments.findBySourceDocumentId(50L)).thenReturn(List.of());
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of());
        when(tasks.fanOut(10L)).thenReturn(2);
    }

    /** What the analysis of document 50 kept or created, as saved. */
    @SuppressWarnings("unchecked")
    private List<Assignment> analyze(TaskExtractDto... found) {
        service.replaceFromDocument(50L, 10L, List.of(found));
        ArgumentCaptor<List<Assignment>> saved = ArgumentCaptor.forClass(List.class);
        verify(assignments).saveAll(saved.capture());
        return saved.getValue();
    }

    private static TaskExtractDto item(String title, LocalDateTime deadline) {
        return new TaskExtractDto(title, null, deadline);
    }

    private static MatchInfo existing(long id, String title, LocalDateTime deadline) {
        return new MatchInfo(id, title, deadline, AssignmentStatus.ACTIVE);
    }

    @Test
    void aiAssignmentsAreActiveHandedOutAndAnnounced() {
        List<Assignment> created = analyze(item("Лаб. 1", OCT_20), item("Лаб. 2", OCT_20.plusDays(7)));

        assertThat(created).extracting(Assignment::getStatus).containsOnly(AssignmentStatus.ACTIVE);
        assertThat(created).allSatisfy(assignment -> assertThat(assignment.getSourceDocument().getId()).isEqualTo(50L));
        verify(tasks).fanOut(10L);
        verify(events).published(10L, "Algorithms", List.of("Лаб. 1", "Лаб. 2"));
        verify(audit).record(isNull(), eq(AuditService.ASSIGNMENT_PUBLISHED), anyString());
    }

    @Test
    void anExactDuplicateIsSkippedAndNotAnnounced() {
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of(
                existing(5L, "Лабораторная работа №1", OCT_20.withHour(9))));

        assertThat(analyze(item("Лаб. работа 1", OCT_20.withHour(23).withMinute(59)))).isEmpty();
        verify(events, never()).published(anyLong(), anyString(), any());
        verify(audit, never()).record(any(), eq(AuditService.ASSIGNMENT_PUBLISHED), anyString());
    }

    @Test
    void sameTitleWithoutDeadlinesIsADuplicateToo() {
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of(existing(5L, "СРС №2", null)));

        assertThat(analyze(item("С.Р.С. 2", null))).isEmpty();
    }

    @Test
    void aDifferentNumberIsCreated() {
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of(
                existing(5L, "Лаб. 1", OCT_20), existing(6L, "Лабораторная работа 12", OCT_20)));

        assertThat(analyze(item("Лаб. 2", OCT_20))).extracting(Assignment::getTitle).containsExactly("Лаб. 2");
        verify(events).published(10L, "Algorithms", List.of("Лаб. 2"));
    }

    @Test
    void theSameTitleOnAnotherDayIsCreated() {
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of(existing(5L, "Лаб. 1", OCT_20)));

        assertThat(analyze(item("Лаб. 1", OCT_20.plusDays(1)))).hasSize(1);
        assertThat(analyze2(item("Лаб. 1", null))).hasSize(1);
    }

    @Test
    void anItemFoundTwiceInOneAnalysisIsCreatedOnce() {
        assertThat(analyze(item("Лаб. 3", OCT_20), item("Лабораторная работа №3", OCT_20.withHour(10))))
                .extracting(Assignment::getTitle).containsExactly("Лаб. 3");
    }

    @Test
    void onlyTheDocumentsOwnCourseIsCompared() {
        when(assignments.findMatchInfoByCourseId(11L)).thenReturn(List.of(existing(9L, "Лаб. 1", OCT_20)));

        assertThat(analyze(item("Лаб. 1", OCT_20))).hasSize(1);
        verify(assignments).findMatchInfoByCourseId(10L);
        verify(assignments, never()).findMatchInfoByCourseId(11L);
    }

    @Test
    void aReAnalysisThatOnlyRewordsATitleKeepsTheAssignment() {
        Assignment lab = Assignment.builder().id(7L).title("Лабораторная работа №1").deadline(OCT_20)
                .status(AssignmentStatus.ACTIVE).course(course).build();
        when(assignments.findBySourceDocumentId(50L)).thenReturn(List.of(lab));
        when(assignments.findMatchInfoByCourseId(10L)).thenReturn(List.of(existing(7L, lab.getTitle(), OCT_20)));

        assertThat(analyze(item("Лаб. 1", OCT_20))).containsExactly(lab);
        assertThat(lab.getTitle()).as("the existing one stays as it is").isEqualTo("Лабораторная работа №1");
        verify(tasks, never()).deleteByAssignmentIdIn(any());
        verify(events, never()).published(anyLong(), anyString(), any());
    }

    /** A second, separate analysis with a fresh service call (the captor of the first is already used). */
    @SuppressWarnings("unchecked")
    private List<Assignment> analyze2(TaskExtractDto... found) {
        service.replaceFromDocument(50L, 10L, List.of(found));
        ArgumentCaptor<List<Assignment>> saved = ArgumentCaptor.forClass(List.class);
        verify(assignments, org.mockito.Mockito.times(2)).saveAll(saved.capture());
        return saved.getValue();
    }
}
