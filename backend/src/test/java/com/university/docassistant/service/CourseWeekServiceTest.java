package com.university.docassistant.service;

import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.CourseWeekDtos.WeeksOverview;
import com.university.docassistant.dto.DocumentResponse;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentType;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.BatchImportRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.CourseWeekRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentSummary;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** US-16 rules of CourseWeekService with the real AccessControlService: who may change what, and what a student sees. */
class CourseWeekServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final CourseWeekRepository weeks = mock(CourseWeekRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final AiClientService ai = mock(AiClientService.class);
    private final WeekSuggestionService suggestions = mock(WeekSuggestionService.class);
    private final AccessControlService access = new AccessControlService(users, courses, documents, enrollments, audit);
    private final CourseWeekService service = new CourseWeekService(access, documents, weeks, suggestions, ai, audit);

    private final User teacher = User.builder().id(2L).email("teacher@uni.kz").role(Role.ROLE_TEACHER).build();
    private final User otherTeacher = User.builder().id(3L).email("other@uni.kz").role(Role.ROLE_TEACHER).build();
    private final User student = User.builder().id(1L).email("student@uni.kz").role(Role.ROLE_STUDENT).build();
    private final Course course = Course.builder().id(10L).title("Базы данных").teacher(teacher).weeksCount(15).build();
    private Document lecture;

    @BeforeEach
    void setUp() {
        for (User user : List.of(teacher, otherTeacher, student)) {
            when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        }
        when(enrollments.existsByStudentIdAndCourseId(1L, 10L)).thenReturn(true);
        when(enrollments.findCourseIdsByStudentId(1L)).thenReturn(List.of(10L));
        when(courses.findIdsByTeacherId(2L)).thenReturn(List.of(10L));
        when(courses.findWithTeacherById(10L)).thenReturn(Optional.of(course));
        lecture = Document.builder().id(50L).originalFileName("lecture-4.pdf").storedFileName("s")
                .contentType("application/pdf").fileSize(1L).category(DocumentCategory.LECTURE)
                .type(DocumentType.OTHER).visibility(DocumentVisibility.COURSE_STUDENTS).aiStatus(AiStatus.DONE)
                .course(course).owner(teacher).uploadedAt(LocalDateTime.now()).suggestedWeek(4)
                .suggestedWeekConfidence(0.9).textSnippet("Нормализация").build();
        when(documents.findWithOwnerById(50L)).thenReturn(Optional.of(lecture));
        when(documents.countByWeek(eq(10L), anyBoolean())).thenReturn(List.of());
    }

    private static void assertError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }

    @Test
    void studentsAndTeachersOfOtherCoursesCannotChangeWeeks() {
        for (String email : List.of("student@uni.kz", "other@uni.kz")) {
            String code = email.startsWith("student") ? ErrorCodes.COURSE_NOT_ENROLLED : ErrorCodes.COURSE_NOT_MANAGED;
            assertError(() -> service.updateWeeksCount(10L, 10, email), HttpStatus.FORBIDDEN, code);
            assertError(() -> service.setTopic(10L, 1, "Тема", email), HttpStatus.FORBIDDEN, code);
            assertError(() -> service.acceptAll(10L, email), HttpStatus.FORBIDDEN, code);
            assertError(() -> service.requestSuggestions(10L, email), HttpStatus.FORBIDDEN, code);
            assertError(() -> service.setDocumentWeek(50L, 3, email), HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED);
            assertError(() -> service.acceptSuggestion(50L, email), HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED);
        }
        assertError(() -> service.weeks(10L, "other@uni.kz"), HttpStatus.FORBIDDEN, ErrorCodes.COURSE_NOT_MANAGED);
        verify(documents, never()).setWeek(anyLong(), any());
        verify(documents, never()).acceptAllSuggestions(anyLong(), anyInt());
        verify(documents, never()).findMaxWeek(anyLong());
        verifyNoInteractions(weeks, suggestions);
    }

    @Test
    void theTeacherSetsAcceptsAndClearsAWeek() {
        assertThat(service.acceptSuggestion(50L, "teacher@uni.kz").weekNumber()).isEqualTo(4);
        verify(documents).setWeek(50L, 4);
        verify(audit).record(eq("teacher@uni.kz"), eq(AuditService.DOCUMENT_WEEK_SET),
                eq("Document 50 \"lecture-4.pdf\": week 4 (AI suggestion accepted)"));

        service.setDocumentWeek(50L, 6, "teacher@uni.kz");
        verify(documents).setWeek(50L, 6);
        verify(audit).record(eq("teacher@uni.kz"), eq(AuditService.DOCUMENT_WEEK_SET),
                eq("Document 50 \"lecture-4.pdf\": week 6 (by hand, AI suggested week 4)"));

        service.setDocumentWeek(50L, null, "teacher@uni.kz");
        verify(documents).setWeek(50L, null);
    }

    @Test
    void weeksOutsideTheCourseAndMissingSuggestionsAreRefused() {
        assertError(() -> service.setDocumentWeek(50L, 16, "teacher@uni.kz"), HttpStatus.BAD_REQUEST,
                ErrorCodes.WEEK_OUT_OF_RANGE);
        assertError(() -> service.setDocumentWeek(50L, 0, "teacher@uni.kz"), HttpStatus.BAD_REQUEST,
                ErrorCodes.WEEK_OUT_OF_RANGE);
        assertError(() -> service.setTopic(10L, 16, "Тема", "teacher@uni.kz"), HttpStatus.BAD_REQUEST,
                ErrorCodes.WEEK_OUT_OF_RANGE);
        assertError(() -> service.updateWeeksCount(10L, 31, "teacher@uni.kz"), HttpStatus.BAD_REQUEST,
                ErrorCodes.WEEK_OUT_OF_RANGE);
        assertError(() -> CourseWeekService.parseWeek("abc"), HttpStatus.BAD_REQUEST, ErrorCodes.WEEK_OUT_OF_RANGE);
        assertError(() -> CourseWeekService.parseWeek("31"), HttpStatus.BAD_REQUEST, ErrorCodes.WEEK_OUT_OF_RANGE);

        lecture.setSuggestedWeek(null);
        assertError(() -> service.acceptSuggestion(50L, "teacher@uni.kz"), HttpStatus.CONFLICT,
                ErrorCodes.WEEK_NO_SUGGESTION);
        lecture.setSuggestedWeek(4);
        lecture.setWeekNumber(2);
        assertError(() -> service.acceptSuggestion(50L, "teacher@uni.kz"), HttpStatus.CONFLICT,
                ErrorCodes.WEEK_NO_SUGGESTION);
        verify(documents, never()).setWeek(anyLong(), any());
    }

    @Test
    void theWeeksCountCannotGoBelowAWeekWithDocuments() {
        when(documents.findMaxWeek(10L)).thenReturn(12);

        assertError(() -> service.updateWeeksCount(10L, 10, "teacher@uni.kz"), HttpStatus.CONFLICT,
                ErrorCodes.WEEK_IN_USE);
        assertThat(course.getWeeksCount()).isEqualTo(15);

        WeeksOverview overview = service.updateWeeksCount(10L, 12, "teacher@uni.kz");
        assertThat(overview.weeksCount()).isEqualTo(12);
        assertThat(overview.weeks()).hasSize(12);
        verify(weeks).deleteAbove(10L, 12);
        verify(documents).clearSuggestionsAbove(10L, 12);
        verify(audit).record("teacher@uni.kz", AuditService.COURSE_WEEKS_CHANGED, "Course 10: weeks count 15 -> 12");
    }

    @Test
    void aStudentSeesOnlyWeeksWithTheirDocumentsAndNoHints() {
        when(documents.countByWeek(10L, false)).thenReturn(List.of(new Object[]{3, 2L}, new Object[]{null, 1L}));
        when(documents.countByWeek(10L, true)).thenReturn(List.of(new Object[]{3, 2L}, new Object[]{5, 1L},
                new Object[]{null, 4L}));
        when(documents.countPendingSuggestions(10L)).thenReturn(2L);

        WeeksOverview forStudent = service.weeks(10L, "student@uni.kz");
        assertThat(forStudent.weeks()).extracting(week -> week.week()).containsExactly(3);
        assertThat(forStudent.weeks().get(0).editedByTeacher()).isNull();
        assertThat(forStudent.withoutWeekCount()).isEqualTo(1);
        assertThat(forStudent.pendingSuggestions()).isNull();

        WeeksOverview forTeacher = service.weeks(10L, "teacher@uni.kz");
        assertThat(forTeacher.weeks()).hasSize(15);
        assertThat(forTeacher.weeks().get(4).documentCount()).isEqualTo(1);
        assertThat(forTeacher.withoutWeekCount()).isEqualTo(4);
        assertThat(forTeacher.pendingSuggestions()).isEqualTo(2);
    }

    @Test
    void aStudentNeverGetsTheSuggestedWeekOfADocument() {
        DocumentService documentService = new DocumentService(documents, mock(TaskRepository.class),
                mock(AssignmentRepository.class), access, mock(StorageService.class), mock(DocumentParserService.class),
                mock(DocumentAnalysisService.class), mock(AiClientService.class), mock(DocumentChunkService.class), audit,
                mock(DocumentClassificationService.class), mock(DocumentProcessingService.class),
                mock(BatchImportRepository.class), mock(AssignmentService.class),
                new DocumentPrivacyService(new PrivacyProperties(true, List.of())));
        lecture.setWeekNumber(null);
        when(documents.findSummariesInCourses(any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(DocumentSummary.of(lecture))));

        List<DocumentResponse> forStudent = documentService.list("student@uni.kz", 10L, false, "none",
                PageRequest.of(0, 10)).content();
        List<DocumentResponse> forTeacher = documentService.list("teacher@uni.kz", 10L, false, null,
                PageRequest.of(0, 10)).content();

        assertThat(forStudent).singleElement().satisfies(row -> assertThat(row.suggestedWeek()).isNull());
        assertThat(forTeacher).singleElement().satisfies(row -> assertThat(row.suggestedWeek()).isEqualTo(4));
        verify(documents).findSummariesInCourses(List.of(10L), false, 10L, false, null, true, PageRequest.of(0, 10));
        assertThat(documentService.get(50L, "student@uni.kz").suggestedWeek()).isNull();
        assertThat(documentService.get(50L, "teacher@uni.kz").suggestedWeek()).isEqualTo(4);
        assertError(() -> documentService.list("student@uni.kz", 10L, false, "0", PageRequest.of(0, 10)),
                HttpStatus.BAD_REQUEST, ErrorCodes.WEEK_OUT_OF_RANGE);
    }

    @Test
    void suggestionsNeedTheAiAndAFreeQueue() {
        when(ai.isConfigured()).thenReturn(false);
        assertError(() -> service.requestSuggestions(10L, "teacher@uni.kz"), HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.AI_NOT_CONFIGURED);

        when(ai.isConfigured()).thenReturn(true);
        when(documents.findIdsWithoutWeek(eq(10L), any())).thenReturn(List.of(50L, 51L));
        assertThat(service.requestSuggestions(10L, "teacher@uni.kz").documents()).isEqualTo(2);
        verify(suggestions).suggestForCourseAsync(10L);

        doThrow(new TaskRejectedException("full")).when(suggestions).suggestForCourseAsync(10L);
        assertError(() -> service.requestSuggestions(10L, "teacher@uni.kz"), HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.AI_QUEUE_FULL);
    }

    @Test
    void acceptAllIsAuditedOnce() {
        when(documents.findPendingSuggestions(10L, 15)).thenReturn(List.of(new Object[]{50L, 4}, new Object[]{51L, 7}));
        when(documents.acceptAllSuggestions(10L, 15)).thenReturn(2);

        assertThat(service.acceptAll(10L, "teacher@uni.kz").accepted()).isEqualTo(2);
        verify(audit).record(eq("teacher@uni.kz"), eq(AuditService.DOCUMENT_WEEK_SET),
                eq("Course 10: 2 AI week suggestion(s) accepted (document -> week): 50 -> 4, 51 -> 7"));
        verify(suggestions, never()).suggest(anyLong());
        verify(ai, never()).chatJson(anyString(), anyString());
    }
}
