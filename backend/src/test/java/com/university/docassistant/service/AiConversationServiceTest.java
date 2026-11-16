package com.university.docassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.university.docassistant.dto.AiConversationDtos.ConversationAnswerResponse;
import com.university.docassistant.dto.AiConversationDtos.ConversationDetail;
import com.university.docassistant.dto.AnswerAssignmentDto;
import com.university.docassistant.dto.DocumentAnswerResponse;
import com.university.docassistant.dto.SourceSnippetDto;
import com.university.docassistant.entity.AiConversation;
import com.university.docassistant.entity.AiConversationTurn;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AiConversationRepository;
import com.university.docassistant.repository.AiConversationRepository.ConversationRow;
import com.university.docassistant.repository.AiConversationTurnRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.service.DocumentQnaService.PriorTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-14 AC-4 and AC-5: saving, continuing and reading conversations; the limit around every question. */
class AiConversationServiceTest {

    private static final String EMAIL = "student@uni.kz";
    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 22, 0);

    private final DocumentQnaService qna = mock(DocumentQnaService.class);
    private final AiConversationRepository conversations = mock(AiConversationRepository.class);
    private final AiConversationTurnRepository turns = mock(AiConversationTurnRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final AccessControlService access = mock(AccessControlService.class);
    private final Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    private final AiQuestionLimiter limiter = new AiQuestionLimiter(2, 0, clock);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AiConversationService service = new AiConversationService(qna, conversations, turns, users, courses,
            access, limiter, objectMapper, clock, mock(PlatformTransactionManager.class));

    private final User student = User.builder().id(5L).email(EMAIL).role(Role.ROLE_STUDENT).build();
    private final Course course = Course.builder().id(3L).title("Базы данных").build();

    private final DocumentAnswerResponse answer = new DocumentAnswerResponse(
            "Лабораторную 2 сдают до 03.11.2026 18:00 [syllabus.pdf, фрагмент 1].", true,
            List.of(new SourceSnippetDto(1, 7L, "syllabus.pdf", 3L, "Базы данных", 0, 0, 40,
                    "Лабораторная работа 2 — до 3 ноября, 18:00")),
            List.of(new AnswerAssignmentDto(1, 41L, 3L, "Базы данных", "Лабораторная работа 2",
                    LocalDateTime.of(2026, 11, 3, 18, 0))),
            List.of(), true);

    @BeforeEach
    void setUp() {
        when(access.currentUser(EMAIL)).thenReturn(student);
        when(users.getReferenceById(5L)).thenReturn(student);
        when(courses.getReferenceById(3L)).thenReturn(course);
        when(conversations.save(any(AiConversation.class))).thenAnswer(invocation -> {
            AiConversation saved = invocation.getArgument(0);
            saved.setId(100L);
            return saved;
        });
    }

    @Test
    void theFirstQuestionStartsAConversationInItsScopeAndSavesTheWholeAnswer() throws Exception {
        when(qna.askCourse(eq(3L), anyString(), eq(EMAIL), anyList())).thenReturn(answer);

        ConversationAnswerResponse response = service.ask("  Когда сдавать\nлабораторную 2? ", 3L, null, EMAIL);

        assertThat(response.conversationId()).isEqualTo(100L);
        assertThat(response.answer()).isEqualTo(answer.answer());
        assertThat(response.sources()).isEqualTo(answer.sources());
        verify(qna).askCourse(3L, "  Когда сдавать\nлабораторную 2? ", EMAIL, List.of());

        ArgumentCaptor<AiConversation> conversation = ArgumentCaptor.forClass(AiConversation.class);
        verify(conversations).save(conversation.capture());
        assertThat(conversation.getValue().getTitle()).isEqualTo("Когда сдавать лабораторную 2?");
        assertThat(conversation.getValue().getCourse()).isSameAs(course);
        assertThat(conversation.getValue().getUser()).isSameAs(student);
        assertThat(conversation.getValue().getUpdatedAt()).isEqualTo(NOW);

        ArgumentCaptor<AiConversationTurn> turn = ArgumentCaptor.forClass(AiConversationTurn.class);
        verify(turns).save(turn.capture());
        assertThat(turn.getValue().getQuestion()).isEqualTo("Когда сдавать\nлабораторную 2?");
        // The saved JSON gives back the same answer, sources and assignments included.
        assertThat(objectMapper.readValue(turn.getValue().getAnswerJson(), DocumentAnswerResponse.class))
                .isEqualTo(answer);
    }

    @Test
    void aContinuedConversationKeepsItsScopeAndGivesTheModelTheEarlierTurns() throws Exception {
        AiConversation saved = AiConversation.builder().id(100L).user(student).course(null).title("t")
                .createdAt(NOW.minusDays(1)).updatedAt(NOW.minusDays(1)).build();
        when(conversations.findOwned(100L, 5L)).thenReturn(Optional.of(saved));
        when(turns.findLatest(eq(100L), any(Pageable.class))).thenReturn(List.of(
                turn(2L, "А лабораторную 2?", answer),
                turn(1L, "Когда экзамен?", DocumentAnswerResponse.withoutSources("Нет информации."))));
        when(qna.askAll(anyString(), eq(EMAIL), anyList())).thenReturn(answer);

        // courseId is ignored: the conversation was started over all courses.
        ConversationAnswerResponse response = service.ask("а третью?", 3L, 100L, EMAIL);

        assertThat(response.conversationId()).isEqualTo(100L);
        verify(qna).askAll("а третью?", EMAIL, List.of(
                new PriorTurn("Когда экзамен?", "Нет информации."),
                new PriorTurn("А лабораторную 2?", answer.answer())));
        verify(qna, never()).askCourse(anyLong(), anyString(), anyString(), anyList());
        verify(conversations, never()).save(any());
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        verify(turns).save(any(AiConversationTurn.class));
    }

    @Test
    void anotherUsersConversationIsNotFound() {
        when(conversations.findOwned(200L, 5L)).thenReturn(Optional.empty());
        ApiException error = catchThrowableOfType(() -> service.ask("Вопрос", null, 200L, EMAIL), ApiException.class);
        assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(error.getCode()).isEqualTo(ErrorCodes.CONVERSATION_NOT_FOUND);
        verify(qna, never()).askAll(anyString(), anyString(), anyList());

        when(conversations.findRowByIdAndUserId(200L, 5L)).thenReturn(Optional.empty());
        assertThat(catchThrowableOfType(() -> service.get(200L, EMAIL), ApiException.class).getCode())
                .isEqualTo(ErrorCodes.CONVERSATION_NOT_FOUND);
        when(conversations.deleteOwned(200L, 5L)).thenReturn(0);
        assertThat(catchThrowableOfType(() -> service.delete(200L, EMAIL), ApiException.class).getCode())
                .isEqualTo(ErrorCodes.CONVERSATION_NOT_FOUND);
    }

    @Test
    void aFullConversationAsksForANewOne() {
        AiConversation saved = AiConversation.builder().id(100L).user(student).title("t").build();
        when(conversations.findOwned(100L, 5L)).thenReturn(Optional.of(saved));
        when(turns.countByConversationId(100L)).thenReturn((long) AiConversationService.MAX_TURNS);
        ApiException error = catchThrowableOfType(() -> service.ask("Ещё", null, 100L, EMAIL), ApiException.class);
        assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.getCode()).isEqualTo(ErrorCodes.CONVERSATION_FULL);
    }

    @Test
    void aFailedAnswerIsNotSavedAndDoesNotCountAgainstTheLimit() {
        when(qna.askAll(anyString(), eq(EMAIL), anyList()))
                .thenThrow(ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AI_UNAVAILABLE, "down"))
                .thenReturn(answer);

        assertThat(catchThrowableOfType(() -> service.ask("Вопрос", null, null, EMAIL), ApiException.class)
                .getCode()).isEqualTo(ErrorCodes.AI_UNAVAILABLE);
        verify(turns, never()).save(any());

        // The limit is 2 an hour: the failed question was given back, so two more fit and the third does not.
        service.ask("Вопрос", null, null, EMAIL);
        service.ask("Вопрос", null, null, EMAIL);
        ApiException limit = catchThrowableOfType(() -> service.ask("Вопрос", null, null, EMAIL), ApiException.class);
        assertThat(limit.getCode()).isEqualTo(ErrorCodes.AI_QUESTION_LIMIT);
        assertThat(service.limits(EMAIL).remainingThisHour()).isZero();
    }

    @Test
    void courseAndDocumentChatsCountAgainstTheSameLimit() {
        service.limited(EMAIL, () -> answer);
        service.limited(EMAIL, () -> answer);
        assertThat(catchThrowableOfType(() -> service.limited(EMAIL, () -> answer), ApiException.class).getCode())
                .isEqualTo(ErrorCodes.AI_QUESTION_LIMIT);
    }

    @Test
    void theHistoryShowsTheTurnsInReadingOrderWithTheirSources() throws Exception {
        when(conversations.findRowByIdAndUserId(100L, 5L)).thenReturn(Optional.of(new ConversationRow(100L,
                "Когда сдавать лабораторную 2?", 3L, "Базы данных", "DB", NOW.minusHours(1), NOW, 2L)));
        when(turns.findLatest(eq(100L), any(Pageable.class))).thenReturn(List.of(
                turn(2L, "А третью?", answer),
                turn(1L, "Когда сдавать лабораторную 2?", answer)));

        ConversationDetail detail = service.get(100L, EMAIL);

        assertThat(detail.courseTitle()).isEqualTo("Базы данных");
        assertThat(detail.turns()).extracting(turn -> turn.question())
                .containsExactly("Когда сдавать лабораторную 2?", "А третью?");
        assertThat(detail.turns().get(0).answer()).isEqualTo(answer);
    }

    @Test
    void aBrokenSavedAnswerIsShownAsSuch() {
        when(conversations.findRowByIdAndUserId(100L, 5L)).thenReturn(Optional.of(new ConversationRow(100L,
                "t", null, null, null, NOW, NOW, 1L)));
        AiConversationTurn broken = AiConversationTurn.builder().id(1L).question("Q").answerJson("{not json")
                .createdAt(NOW).build();
        when(turns.findLatest(eq(100L), any(Pageable.class))).thenReturn(List.of(broken));

        assertThat(service.get(100L, EMAIL).turns().get(0).answer().hasAnswer()).isFalse();
    }

    @Test
    void theTitleIsTheFirstQuestionOnOneLineShortened() {
        assertThat(AiConversationService.title("  Когда\n\nэкзамен?  ")).isEqualTo("Когда экзамен?");
        assertThat(AiConversationService.title(" ")).isEqualTo("Без названия");
        String title = AiConversationService.title("слово ".repeat(60));
        assertThat(title).hasSize(AiConversation.MAX_TITLE_LENGTH).endsWith("…");
    }

    private AiConversationTurn turn(long id, String question, DocumentAnswerResponse response) throws Exception {
        return AiConversationTurn.builder().id(id).question(question)
                .answerJson(objectMapper.writeValueAsString(response)).createdAt(NOW.minusMinutes(id)).build();
    }
}
