package com.university.docassistant.service;

import com.university.docassistant.config.AiProperties;
import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.AnswerAssignmentDto;
import com.university.docassistant.dto.AnswerTaskDto;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.repository.TaskRepository.QnaTask;
import com.university.docassistant.dto.DocumentAnswerResponse;
import com.university.docassistant.dto.SourceSnippetDto;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.AssignmentRepository.QnaAssignment;
import com.university.docassistant.repository.DocumentChunkRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.DocumentChunkRepository.ChunkHit;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.AccessControlService.Scope;
import com.university.docassistant.security.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentQnaServiceTest {

    private static final String EMAIL = "student@uni.kz";
    private static final String QUESTION = "Когда сдавать лабораторную работу?";

    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final AiClientService ai = mock(AiClientService.class);
    private final AssignmentRepository assignments = mock(AssignmentRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 12, 0);
    private final DocumentQnaService service = new DocumentQnaService(documentService, chunks, assignments, tasks, access, ai,
            new AiProperties(true, "key", "m", "http://x", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 1, 2, 1),
            new DocumentPrivacyService(new PrivacyProperties(null, null)),
            Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));

    private final Document document = Document.builder().id(7L).textSnippet("Силлабус").build();
    private final User student = User.builder().id(5L).email(EMAIL).role(Role.ROLE_STUDENT).build();

    record Hit(Long documentId, String documentName, Long courseId, String courseTitle, Integer chunkIndex,
               Integer startOffset, Integer endOffset, String content, Double score) implements ChunkHit {
        public Long getDocumentId() { return documentId; }
        public String getDocumentName() { return documentName; }
        public Long getCourseId() { return courseId; }
        public String getCourseTitle() { return courseTitle; }
        public Integer getChunkIndex() { return chunkIndex; }
        public Integer getStartOffset() { return startOffset; }
        public Integer getEndOffset() { return endOffset; }
        public String getContent() { return content; }
        public Double getScore() { return score; }
    }

    private static Hit hit(long documentId, int index, String content) {
        return new Hit(documentId, "doc" + documentId + ".pdf", 3L, "Базы данных", index, index * 850,
                index * 850 + content.length(), content, 0.5);
    }

    record Task(Long id, Long courseId, String courseTitle, String title, LocalDateTime deadline, Integer maxScore,
                Boolean allowLate, Double score) implements QnaAssignment {
        public Long getId() { return id; }
        public Long getCourseId() { return courseId; }
        public String getCourseTitle() { return courseTitle; }
        public String getTitle() { return title; }
        public LocalDateTime getDeadline() { return deadline; }
        public Integer getMaxScore() { return maxScore; }
        public Boolean getAllowLate() { return allowLate; }
        public Double getScore() { return score; }
    }

    private static final Task LAB2 = new Task(41L, 3L, "Базы данных", "Лабораторная работа 2",
            LocalDateTime.of(2026, 12, 27, 23, 59), 10, false, 0.4);
    private static final Task PROJECT = new Task(42L, 3L, "Базы данных", "Курсовой проект", null, null, true, 0.0);

    @BeforeEach
    void setUp() {
        when(ai.isConfigured()).thenReturn(true);
        when(documentService.getViewable(7L, EMAIL)).thenReturn(document);
        when(access.currentUser(EMAIL)).thenReturn(student);
    }

    @Test
    void answersFromRelevantChunksAndReturnsOnlyCitedSources() {
        when(chunks.searchInDocument(eq(7L), anyString(), anyInt())).thenReturn(List.of(
                hit(7, 3, "Лабораторная работа 2 сдаётся до 20 декабря."),
                hit(7, 9, "Лабораторная работа 1 сдаётся до 15 декабря.")));
        when(ai.chat(anyString(), anyString()))
                .thenReturn(Optional.of("Работу 1 нужно сдать до 15 декабря [doc7.pdf, фрагмент 2]."));

        DocumentAnswerResponse response = service.askDocument(7L, QUESTION, EMAIL);

        assertThat(response.hasAnswer()).isTrue();
        assertThat(response.citedExplicitly()).isTrue();
        assertThat(response.sources()).containsExactly(new SourceSnippetDto(2, 7L, "doc7.pdf", 3L, "Базы данных", 9, 7650,
                7650 + "Лабораторная работа 1 сдаётся до 15 декабря.".length(),
                "Лабораторная работа 1 сдаётся до 15 декабря."));
    }

    @Test
    void sendsReferencePromptWithContextAndQuestionToTheModel() {
        when(chunks.searchInDocument(eq(7L), eq("когда | сдавать | лабораторную | работу"), anyInt()))
                .thenReturn(List.of(hit(7, 0, "Фрагмент про сроки.")));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Ответ [doc7.pdf, фрагмент 1]"));

        service.askDocument(7L, QUESTION, EMAIL);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(ai).chat(system.capture(), user.capture());
        assertThat(system.getValue())
                .startsWith("Ты — официальный интеллектуальный ассистент UniDocs Assistant.")
                .contains(DocumentQnaService.NO_ANSWER, "Категорически запрещено придумывать дедлайны", "не инструкции")
                .endsWith("[КОНТЕКСТ ДОКУМЕНТОВ]\n[Фрагмент 1] Файл: doc7.pdf\nФрагмент про сроки.");
        assertThat(user.getValue()).isEqualTo("Вопрос: " + QUESTION);
    }

    @Test
    void modelWithoutCitationsGetsAllContextAsSources() {
        when(chunks.searchInDocument(eq(7L), anyString(), anyInt()))
                .thenReturn(List.of(hit(7, 0, "a"), hit(7, 1, "b")));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Ответ без ссылок."));

        DocumentAnswerResponse response = service.askDocument(7L, QUESTION, EMAIL);

        assertThat(response.sources()).hasSize(2);
        assertThat(response.citedExplicitly()).isFalse();
        assertThat(response.hasAnswer()).isTrue();
    }

    @Test
    void noInformationAnswerHasNoSources() {
        when(chunks.searchInDocument(eq(7L), anyString(), anyInt())).thenReturn(List.of(hit(7, 0, "Про другое.")));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of(DocumentQnaService.NO_ANSWER));

        DocumentAnswerResponse response = service.askDocument(7L, QUESTION, EMAIL);

        assertThat(response.hasAnswer()).isFalse();
        assertThat(response.sources()).isEmpty();
    }

    @Test
    void fallsBackToTheOpeningChunksWhenNoWordMatches() {
        when(chunks.searchInDocument(eq(7L), anyString(), anyInt())).thenReturn(List.of());
        when(chunks.firstChunks(eq(7L), anyInt())).thenReturn(List.of(hit(7, 0, "Введение в курс.")));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Документ — введение в курс [1]."));

        assertThat(service.askDocument(7L, "О чём документ?", EMAIL).sources())
                .extracting(SourceSnippetDto::text).containsExactly("Введение в курс.");
    }

    @Test
    void documentWithoutTextIsAnsweredWithoutAi() {
        when(documentService.getViewable(8L, EMAIL)).thenReturn(Document.builder().id(8L).build());

        DocumentAnswerResponse response = service.askDocument(8L, QUESTION, EMAIL);

        assertThat(response).isEqualTo(DocumentAnswerResponse.withoutSources(DocumentQnaService.NO_TEXT_ANSWER));
        verify(ai, never()).chat(anyString(), anyString());
    }

    @Test
    void accessDeniedComesBeforeAnythingElse() {
        when(documentService.getViewable(9L, EMAIL)).thenThrow(ApiException.forbidden(ErrorCodes.ACCESS_DENIED, "Access denied"));

        assertThatThrownBy(() -> service.askDocument(9L, QUESTION, EMAIL))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(chunks);
    }

    @Test
    void disabledAiGives503() {
        when(ai.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.askDocument(7L, QUESTION, EMAIL))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(chunks);
    }

    @Test
    void unreachableAiGives503() {
        when(chunks.searchInDocument(eq(7L), anyString(), anyInt())).thenReturn(List.of(hit(7, 0, "a")));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.askDocument(7L, QUESTION, EMAIL))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getMessage()).contains("temporarily unavailable");
                });
    }

    @Test
    void courseAnswerSearchesOnlyThatCourseAndHidesTeacherOnlyFromStudents() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(3L, 4L), false));
        when(chunks.searchInCourses(eq(List.of(3L)), eq(false), anyString(), anyInt()))
                .thenReturn(List.of(hit(10, 0, "Экзамен в декабре."), hit(11, 4, "Проект до мая.")));
        when(ai.chat(anyString(), anyString()))
                .thenReturn(Optional.of("Экзамен в декабре [doc10.pdf, фрагмент 1], проект до мая [doc11.pdf, фрагмент 2]."));

        DocumentAnswerResponse response = service.askCourse(3L, "Когда экзамен и проект?", EMAIL);

        verify(access).authorizeCourse(student, 3L, Permission.ASK_AI);
        assertThat(response.sources()).extracting(SourceSnippetDto::documentId, SourceSnippetDto::documentName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(10L, "doc10.pdf"),
                        org.assertj.core.groups.Tuple.tuple(11L, "doc11.pdf"));
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(ai).chat(system.capture(), anyString());
        assertThat(system.getValue()).contains("[Фрагмент 2] Файл: doc11.pdf\nПроект до мая.");
    }

    @Test
    void courseOfOthersIsRefusedBeforeRetrieval() {
        when(access.authorizeCourse(student, 3L, Permission.ASK_AI))
                .thenThrow(ApiException.forbidden(ErrorCodes.COURSE_NOT_ENROLLED, "You are not enrolled in this course"));

        assertThatThrownBy(() -> service.askCourse(3L, QUESTION, EMAIL))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(chunks);
        verify(ai, never()).chat(anyString(), anyString());
    }

    @Test
    void unknownCourseGives404() {
        when(access.authorizeCourse(student, 4L, Permission.ASK_AI)).thenThrow(ApiException.notFound(ErrorCodes.COURSE_NOT_FOUND, "Course not found"));

        assertThatThrownBy(() -> service.askCourse(4L, QUESTION, EMAIL))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void courseWithoutMatchesAnswersWithoutAi() {
        User teacher = User.builder().id(6L).email(EMAIL).role(Role.ROLE_TEACHER).build();
        when(access.currentUser(EMAIL)).thenReturn(teacher);
        when(access.scope(teacher)).thenReturn(new Scope(false, List.of(3L), true));
        when(chunks.searchInCourses(eq(List.of(3L)), eq(true), anyString(), anyInt())).thenReturn(List.of());

        DocumentAnswerResponse response = service.askCourse(3L, QUESTION, EMAIL);

        assertThat(response).isEqualTo(DocumentAnswerResponse.withoutSources(DocumentQnaService.NO_ANSWER));
        verify(ai, never()).chat(anyString(), anyString());
    }

    @Test
    void globalQuestionSearchesOnlyTheAllowedCourses() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(3L, 4L), false));
        when(chunks.searchInCourses(eq(List.of(3L, 4L)), eq(false), anyString(), anyInt()))
                .thenReturn(List.of(hit(10, 0, "Экзамен в декабре.")));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Экзамен в декабре [doc10.pdf, фрагмент 1]."));

        assertThat(service.askAll(QUESTION, EMAIL).hasAnswer()).isTrue();
    }

    @Test
    void globalQuestionWithoutCoursesAnswersWithoutRetrieval() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(), false));

        assertThat(service.askAll(QUESTION, EMAIL))
                .isEqualTo(DocumentAnswerResponse.withoutSources(DocumentQnaService.NO_ANSWER));
        verifyNoInteractions(chunks);
    }

    @Test
    void buildsSafeOrQueryFromQuestionWords() {
        assertThat(DocumentQnaService.toTsQuery("Что такое O(n log n)? & | ! 'x'"))
                .contains("что | такое | log");
        assertThat(DocumentQnaService.toTsQuery("?! ...")).isEmpty();
    }

    @Test
    void keepsNumbersOfAnyLengthButNotSingleLetters() {
        assertThat(DocumentQnaService.toTsQuery("Когда сдавать лабораторную 2?"))
                .contains("когда | сдавать | лабораторную | 2");
        assertThat(DocumentQnaService.toTsQuery("Задача 7 и 12, вариант б")).contains("задача | 7 | 12 | вариант");
        assertThat(DocumentQnaService.toTsQuery("а я б")).isEmpty();
    }

    @Test
    void assignmentsBlockComesOnlyFromTheAllowedCourses() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(3L, 4L), false));
        when(chunks.searchInCourses(eq(List.of(3L, 4L)), eq(false), anyString(), anyInt()))
                .thenReturn(List.of(hit(10, 0, "Лабораторная работа 2 сдаётся до 20 декабря.")));
        when(assignments.findForQnaInCourses(eq(List.of(3L, 4L)), anyString(), any(), anyInt()))
                .thenReturn(List.of(LAB2, PROJECT));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of(
                "Срок изменён: лабораторную 2 нужно сдать до 27.12.2026 23:59 [задание 1] (в документе — 20 декабря "
                        + "[doc10.pdf, фрагмент 1])."));

        DocumentAnswerResponse response = service.askAll("Когда сдавать лабораторную 2?", EMAIL);

        verify(assignments).findForQnaInCourses(List.of(3L, 4L), "когда | сдавать | лабораторную | 2", NOW,
                DocumentQnaService.CONTEXT_ASSIGNMENTS);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(ai).chat(system.capture(), anyString());
        assertThat(system.getValue())
                .contains("Если дата в документе расходится с этим блоком, верна дата из блока", "[задание N]")
                .endsWith("""
                        [АКТУАЛЬНЫЕ ЗАДАНИЯ КУРСА]
                        [Задание 1] Курс: Базы данных · Название: Лабораторная работа 2 · Срок: 27.12.2026 23:59 \
                        · Максимальный балл: 10 · После срока: не принимается
                        [Задание 2] Курс: Базы данных · Название: Курсовой проект · Срок: срок не указан \
                        · Максимальный балл: не указан · После срока: принимается""");
        assertThat(response.citedExplicitly()).isTrue();
        assertThat(response.sources()).extracting(SourceSnippetDto::fragment).containsExactly(1);
        assertThat(response.assignments()).containsExactly(new AnswerAssignmentDto(1, 41L, 3L, "Базы данных",
                "Лабораторная работа 2", LocalDateTime.of(2026, 12, 27, 23, 59)));
    }

    @Test
    void courseQuestionListsOnlyThatCoursesAssignments() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(3L, 4L), false));
        when(chunks.searchInCourses(eq(List.of(3L)), eq(false), anyString(), anyInt()))
                .thenReturn(List.of(hit(10, 0, "a")));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Ответ [1]"));

        service.askCourse(3L, QUESTION, EMAIL);

        verify(assignments).findForQnaInCourses(eq(List.of(3L)), anyString(), eq(NOW), anyInt());
    }

    @Test
    void documentQuestionListsTheAssignmentsFoundInThatDocument() {
        when(chunks.searchInDocument(eq(7L), anyString(), anyInt())).thenReturn(List.of(hit(7, 0, "a")));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Ответ [1]"));

        service.askDocument(7L, QUESTION, EMAIL);

        verify(assignments).findForQnaInDocument(eq(7L), anyString(), eq(NOW), anyInt());
        verify(assignments, never()).findForQnaInCourses(any(), anyString(), any(), anyInt());
    }

    @Test
    void questionWithoutFragmentsStillGoesToTheAiWhenThereAreAssignments() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(3L), false));
        when(chunks.searchInCourses(any(), eq(false), anyString(), anyInt())).thenReturn(List.of());
        when(assignments.findForQnaInCourses(eq(List.of(3L)), anyString(), any(), anyInt()))
                .thenReturn(List.of(PROJECT));
        when(ai.chat(anyString(), anyString()))
                .thenReturn(Optional.of("Курсовой проект пока без срока [задание 1]."));

        DocumentAnswerResponse response = service.askAll("Что с курсовым?", EMAIL);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(ai).chat(system.capture(), anyString());
        assertThat(system.getValue()).contains("Подходящих фрагментов документов не найдено.",
                "[Задание 1] Курс: Базы данных · Название: Курсовой проект");
        assertThat(response.hasAnswer()).isTrue();
        assertThat(response.sources()).isEmpty();
        assertThat(response.assignments()).extracting(AnswerAssignmentDto::assignmentId).containsExactly(42L);
        assertThat(response.citedExplicitly()).isTrue();
    }

    @Test
    void assignmentTitlesAreMaskedBeforeTheyReachTheAi() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(3L), false));
        when(assignments.findForQnaInCourses(any(), anyString(), any(), anyInt())).thenReturn(List.of(
                new Task(43L, 3L, "Базы данных", "Отчёт Иванова 990101300013", null, null, true, 0.0)));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Ответ [задание 1]"));

        DocumentAnswerResponse response = service.askAll("Какие задания?", EMAIL);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(ai).chat(system.capture(), anyString());
        assertThat(system.getValue()).doesNotContain("990101300013").contains("Отчёт Иванова [ИИН скрыт]");
        // The answer shows the title as the student sees it in their tasks.
        assertThat(response.assignments()).extracting(AnswerAssignmentDto::title)
                .containsExactly("Отчёт Иванова 990101300013");
    }

    @Test
    void noFragmentsAndNoAssignmentsAnswersWithoutAi() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(3L), false));

        assertThat(service.askAll("Что с курсовым?", EMAIL))
                .isEqualTo(DocumentAnswerResponse.withoutSources(DocumentQnaService.NO_ANSWER));
        verify(ai, never()).chat(anyString(), anyString());
    }

    @Test
    void answerCitingOnlyAnAssignmentHasNoFragmentSources() {
        when(chunks.searchInDocument(eq(7L), anyString(), anyInt())).thenReturn(List.of(hit(7, 0, "a"), hit(7, 1, "b")));
        when(assignments.findForQnaInDocument(eq(7L), anyString(), any(), anyInt())).thenReturn(List.of(LAB2));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("До 27.12.2026 [Задание 1]."));

        DocumentAnswerResponse response = service.askDocument(7L, QUESTION, EMAIL);

        assertThat(response.citedExplicitly()).isTrue();
        assertThat(response.sources()).isEmpty();
        assertThat(response.assignments()).extracting(AnswerAssignmentDto::number).containsExactly(1);
    }

    @Test
    void parsesAssignmentCitations() {
        assertThat(DocumentQnaService.citedAssignments(
                "a [задание 2] b [Задания 1, 3] c [doc.pdf, фрагмент 1; задание 4] d [задание 9] e [2] f [Задание 0]", 5))
                .containsExactly(2, 1, 3, 4);
        assertThat(DocumentQnaService.citedFragments("[задание 2] [doc.pdf, фрагмент 1; задание 4]", 5))
                .containsExactly(1);
        assertThat(DocumentQnaService.citedAssignments("[задание 99999999999]", 5)).isEmpty();
    }

    @Test
    void parsesCitationFormats() {
        assertThat(DocumentQnaService.citedFragments("a [1] b [2, 3] c [Фрагмент 4] d [9] e [0]", 5))
                .containsExactly(1, 2, 3, 4);
        assertThat(DocumentQnaService.citedFragments(
                "[Practice 04.pdf, фрагмент 2] и [Lab-3.docx, Фрагменты 1, 3] и [Syllabus 2026.pdf]", 5))
                .containsExactly(2, 1, 3);
    }
    // --- Own tasks, the date, duplicates ---------------------------------------------------------------------------

    private static final QnaTask OVERDUE_LAB = new QnaTask(61L, "Лабораторная работа 3", NOW.minusDays(2),
            TaskStatus.PENDING, 3L, "Базы данных", AssignmentStatus.ACTIVE, false, null, 10, false, true);
    private static final QnaTask HANDED_IN = new QnaTask(62L, "Эссе", NOW.minusDays(1), TaskStatus.SUBMITTED, 3L,
            "Базы данных", AssignmentStatus.ACTIVE, true, null, null, false, false);
    private static final QnaTask GRADED = new QnaTask(63L, "Тест 1", NOW.minusDays(9), TaskStatus.GRADED, 3L,
            "Базы данных", AssignmentStatus.ACTIVE, false, new BigDecimal("8.50"), 10, false, false);

    private String systemPromptSent() {
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(ai).chat(system.capture(), anyString());
        return system.getValue();
    }

    @Test
    void aStudentsOwnTasksGoIntoThePromptWithTheirStates() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(3L, 4L), false));
        when(tasks.findForQna(eq(5L), eq(List.of(3L, 4L)), eq(NOW), any()))
                .thenReturn(List.of(OVERDUE_LAB, HANDED_IN, GRADED));
        when(ai.chat(anyString(), anyString()))
                .thenReturn(Optional.of("Просрочена лабораторная работа 3 [задача 1]; эссе сдано [задача 2]."));

        DocumentAnswerResponse response = service.askAll("Какие у меня пропущенные задания или дедлайны?", EMAIL);

        assertThat(systemPromptSent())
                .contains("Сейчас: 13.11.2026 12:00. Пользователь — студент",
                        "Если просроченных задач нет, прямо так и скажи", "[задача N]")
                .endsWith("""
                        [МОИ ЗАДАЧИ]
                        [Задача 1] Курс: Базы данных · Название: Лабораторная работа 3 · Срок: 11.11.2026 12:00 \
                        · Состояние: просрочено · Максимальный балл: 10
                        [Задача 2] Курс: Базы данных · Название: Эссе · Срок: 12.11.2026 12:00 \
                        · Состояние: сдано с опозданием
                        [Задача 3] Курс: Базы данных · Название: Тест 1 · Срок: 04.11.2026 12:00 \
                        · Состояние: оценено · Балл: 8.5 из 10""");
        assertThat(response.hasAnswer()).isTrue();
        assertThat(response.citedExplicitly()).isTrue();
        assertThat(response.tasks()).containsExactly(
                new AnswerTaskDto(1, 61L, 3L, "Базы данных", "Лабораторная работа 3", NOW.minusDays(2), "OVERDUE"),
                new AnswerTaskDto(2, 62L, 3L, "Базы данных", "Эссе", NOW.minusDays(1), "LATE"));
    }

    @Test
    void tasksAreOnlyTheCurrentStudentsAndOnlyOfTheAskedCourse() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(3L, 4L), false));
        when(tasks.findForQna(anyLong(), any(), any(), any())).thenReturn(List.of(OVERDUE_LAB));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Ответ [задача 1]"));

        service.askCourse(3L, "Что я пропустил?", EMAIL);

        verify(tasks).findForQna(eq(5L), eq(List.of(3L)), eq(NOW), any());
        verify(tasks, never()).findForQna(org.mockito.ArgumentMatchers.longThat(id -> id != 5L), any(), any(), any());
    }

    @Test
    void aQuestionWithOnlyTasksStillGoesToTheAi() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(3L), false));
        when(tasks.findForQna(eq(5L), eq(List.of(3L)), any(), any())).thenReturn(List.of(HANDED_IN));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Просроченных задач нет [задача 1]."));

        DocumentAnswerResponse response = service.askAll("Есть ли у меня просрочки?", EMAIL);

        assertThat(systemPromptSent()).contains("Подходящих фрагментов документов не найдено.", "[МОИ ЗАДАЧИ]");
        assertThat(response.tasks()).extracting(AnswerTaskDto::state).containsExactly("LATE");
    }

    @Test
    void teachersHaveNoTasksBlock() {
        User teacher = User.builder().id(6L).email(EMAIL).role(Role.ROLE_TEACHER).build();
        when(access.currentUser(EMAIL)).thenReturn(teacher);
        when(access.scope(teacher)).thenReturn(new Scope(false, List.of(3L), true));
        when(assignments.findForQnaInCourses(any(), anyString(), any(), anyInt())).thenReturn(List.of(LAB2));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of(
                "Личные задачи есть только у студентов. Ближайший дедлайн — 27.12.2026 [задание 1]."));

        service.askAll("Какие у меня пропущенные задания?", EMAIL);

        assertThat(systemPromptSent()).contains("Пользователь — преподаватель или деканат: личных задач у него нет",
                "личные задачи есть только у студентов").doesNotContain("[МОИ ЗАДАЧИ]\n");
        verify(tasks, never()).findForQna(anyLong(), any(), any(), any());
    }

    @Test
    void theCurrentMomentComesFromTheSharedClock() {
        when(chunks.searchInDocument(eq(7L), anyString(), anyInt())).thenReturn(List.of(hit(7, 0, "a")));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Ответ [1]"));

        service.askDocument(7L, QUESTION, EMAIL);

        assertThat(systemPromptSent()).contains("Сейчас: 13.11.2026 12:00.");
    }

    @Test
    void fragmentsWithTheSameTextAreKeptOnceAndTheNextOneTakesTheirPlace() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(3L), false));
        List<ChunkHit> ranked = new java.util.ArrayList<>();
        ranked.add(hit(10, 0, "Экзамен 20 декабря."));
        ranked.add(hit(11, 0, "Экзамен  20 декабря.\n")); // the same file uploaded again
        for (int i = 1; i <= 8; i++) {
            ranked.add(hit(12, i, "Фрагмент " + i));
        }
        when(chunks.searchInCourses(eq(List.of(3L)), eq(false), anyString(), anyInt())).thenReturn(ranked);
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Ответ без ссылок."));

        DocumentAnswerResponse response = service.askAll("Когда экзамен?", EMAIL);

        verify(chunks).searchInCourses(eq(List.of(3L)), eq(false), anyString(), eq(DocumentQnaService.COURSE_CONTEXT_CHUNKS * 3));
        assertThat(response.sources()).hasSize(DocumentQnaService.COURSE_CONTEXT_CHUNKS)
                .extracting(SourceSnippetDto::documentId).doesNotContain(11L).contains(12L);
        assertThat(systemPromptSent()).doesNotContain("Файл: doc11.pdf").contains("[Фрагмент 8] Файл: doc12.pdf");
    }

    @Test
    void taskStatesFollowMyTasks() {
        QnaTask base = new QnaTask(1L, "t", NOW, TaskStatus.PENDING, 3L, "c", AssignmentStatus.ACTIVE, false, null,
                null, false, false);
        assertThat(DocumentQnaService.taskState(base)).isEqualTo("NOT_STARTED");
        assertThat(DocumentQnaService.taskState(new QnaTask(1L, "t", NOW, TaskStatus.IN_PROGRESS, 3L, "c",
                AssignmentStatus.ACTIVE, false, null, null, false, false))).isEqualTo("IN_PROGRESS");
        assertThat(DocumentQnaService.taskState(new QnaTask(1L, "t", NOW, TaskStatus.PENDING, 3L, "c",
                AssignmentStatus.CLOSED, false, null, null, false, false))).isEqualTo("CLOSED");
        assertThat(DocumentQnaService.taskState(new QnaTask(1L, "t", NOW, TaskStatus.PENDING, 3L, "c",
                AssignmentStatus.ACTIVE, false, null, null, true, false))).isEqualTo("BEFORE_ENROLLMENT");
        assertThat(DocumentQnaService.taskState(new QnaTask(1L, "t", NOW, TaskStatus.SUBMITTED, 3L, "c",
                AssignmentStatus.CLOSED, false, null, null, false, false))).isEqualTo("SUBMITTED");
        assertThat(DocumentQnaService.taskState(OVERDUE_LAB)).isEqualTo("OVERDUE");
        assertThat(DocumentQnaService.taskState(GRADED)).isEqualTo("GRADED");
        assertThat(DocumentQnaService.citedTasks("[задача 2] [Задачи 1, 3] [задание 1] [задача 9]", 3))
                .containsExactly(2, 1, 3);
    }

    @Test
    void taskTitlesAreMaskedForTheAi() {
        when(access.scope(student)).thenReturn(new Scope(false, List.of(3L), false));
        when(tasks.findForQna(eq(5L), any(), any(), any())).thenReturn(List.of(new QnaTask(64L,
                "Отчёт 990101300013", null, TaskStatus.PENDING, 3L, "Базы данных", AssignmentStatus.ACTIVE, false,
                null, null, false, false)));
        when(ai.chat(anyString(), anyString())).thenReturn(Optional.of("Ответ [задача 1]"));

        service.askAll("Что мне сдавать?", EMAIL);

        assertThat(systemPromptSent()).doesNotContain("990101300013").contains("Отчёт [ИИН скрыт] · Срок: срок не указан");
    }
}
