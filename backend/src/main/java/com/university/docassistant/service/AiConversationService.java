package com.university.docassistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.university.docassistant.dto.AiConversationDtos.ConversationAnswerResponse;
import com.university.docassistant.dto.AiConversationDtos.ConversationDetail;
import com.university.docassistant.dto.AiConversationDtos.LimitsResponse;
import com.university.docassistant.dto.AiConversationDtos.TurnView;
import com.university.docassistant.dto.DocumentAnswerResponse;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.entity.AiConversation;
import com.university.docassistant.entity.AiConversationTurn;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * US-14: the AI assistant's saved conversations (POST /ai/ask, GET/DELETE /ai/conversations). Every question to
 * /ai/ask is saved with its answer in a conversation of the asking user; sending {@code conversationId} continues
 * one, with its earlier turns given to the model so a follow-up ("а третья?") is understood. A conversation keeps the
 * scope it was started with (one course or all of the user's courses) and is visible only to its owner: another
 * user's id is 404, like one that does not exist.
 * <p>
 * The AI is called outside any transaction (as everywhere in Q&A); the turn is written afterwards in a short one, so a
 * question the AI could not answer (503) leaves no trace. Each question also counts against the user's limits
 * (AiQuestionLimiter); a failed one is given back.
 */
@Slf4j
@Service
public class AiConversationService {

    /** Questions per conversation; then 409 CONVERSATION_FULL ("начните новый разговор"). */
    public static final int MAX_TURNS = 100;

    private final DocumentQnaService qna;
    private final AiConversationRepository conversations;
    private final AiConversationTurnRepository turns;
    private final UserRepository users;
    private final CourseRepository courses;
    private final AccessControlService access;
    private final AiQuestionLimiter limiter;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AiConversationService(DocumentQnaService qna, AiConversationRepository conversations,
                                 AiConversationTurnRepository turns, UserRepository users, CourseRepository courses,
                                 AccessControlService access, AiQuestionLimiter limiter, ObjectMapper objectMapper,
                                 Clock clock, PlatformTransactionManager transactionManager) {
        this.qna = qna;
        this.conversations = conversations;
        this.turns = turns;
        this.users = users;
        this.courses = courses;
        this.access = access;
        this.limiter = limiter;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Answers {@code question} and saves it. Without {@code conversationId} a new conversation is started with
     * {@code courseId} (null: all of the user's courses); with it, that conversation continues in its own scope and
     * {@code courseId} is ignored.
     */
    public ConversationAnswerResponse ask(String question, Long courseId, Long conversationId, String email) {
        User user = access.currentUser(email);
        Long scope = courseId;
        List<PriorTurn> history = List.of();
        if (conversationId != null) {
            AiConversation conversation = owned(conversationId, user);
            scope = conversation.getCourse() == null ? null : conversation.getCourse().getId();
            if (turns.countByConversationId(conversationId) >= MAX_TURNS) {
                throw ApiException.conflict(ErrorCodes.CONVERSATION_FULL,
                        "В разговоре уже " + MAX_TURNS + " вопросов. Начните новый разговор.");
            }
            history = history(conversationId);
        }

        AiQuestionLimiter.Permit permit = limiter.acquire(user.getId());
        DocumentAnswerResponse answer;
        try {
            answer = scope == null
                    ? qna.askAll(question, email, history)
                    : qna.askCourse(scope, question, email, history);
        } catch (RuntimeException ex) {
            // Access denied, the AI off, busy or failing: nothing of the shared quota was used for an answer.
            permit.giveBack();
            throw ex;
        }

        Long savedId = save(user.getId(), scope, conversationId, question, answer);
        return ConversationAnswerResponse.of(savedId, answer);
    }

    /**
     * A question of the course chat or the document chat, which are not saved: counted against the same limits as
     * /ai/ask and given back when it fails.
     */
    public DocumentAnswerResponse limited(String email, Supplier<DocumentAnswerResponse> question) {
        User user = access.currentUser(email);
        AiQuestionLimiter.Permit permit = limiter.acquire(user.getId());
        try {
            return question.get();
        } catch (RuntimeException ex) {
            permit.giveBack();
            throw ex;
        }
    }

    /** "История": the user's own conversations, the latest question first. */
    @Transactional(readOnly = true)
    public PageResponse<ConversationRow> list(String email, Pageable pageable) {
        User user = access.currentUser(email);
        Pageable page = PageRequest.of(pageable.getPageNumber(), Math.min(Math.max(pageable.getPageSize(), 1), 50));
        return PageResponse.of(conversations.findRowsByUserId(user.getId(), page));
    }

    /** One of the user's own conversations with its latest {@value #MAX_TURNS} turns; 404 for any other. */
    @Transactional(readOnly = true)
    public ConversationDetail get(Long id, String email) {
        User user = access.currentUser(email);
        ConversationRow row = conversations.findRowByIdAndUserId(id, user.getId())
                .orElseThrow(AiConversationService::notFound);
        List<AiConversationTurn> latest = new ArrayList<>(turns.findLatest(id, PageRequest.of(0, MAX_TURNS)));
        Collections.reverse(latest);
        List<TurnView> views = latest.stream()
                .map(turn -> new TurnView(turn.getId(), turn.getQuestion(), turn.getCreatedAt(), read(turn)))
                .toList();
        return new ConversationDetail(row.id(), row.title(), row.courseId(), row.courseTitle(), row.courseCode(),
                row.createdAt(), row.updatedAt(), views);
    }

    /** Deletes one of the user's own conversations with its turns; 404 for any other. */
    @Transactional
    public void delete(Long id, String email) {
        User user = access.currentUser(email);
        if (conversations.deleteOwned(id, user.getId()) == 0) {
            throw notFound();
        }
    }

    public LimitsResponse limits(String email) {
        User user = access.currentUser(email);
        AiQuestionLimiter.Remaining remaining = limiter.remaining(user.getId());
        return new LimitsResponse(remaining.hour(), remaining.day(), remaining.perHour(), remaining.perDay());
    }

    /** The conversation name: the first question on one line, shortened to {@value AiConversation#MAX_TITLE_LENGTH}. */
    static String title(String question) {
        String line = question == null ? "" : question.replaceAll("\\s+", " ").strip();
        if (line.isEmpty()) {
            return "Без названия";
        }
        int max = AiConversation.MAX_TITLE_LENGTH;
        return line.length() <= max ? line : line.substring(0, max - 1).stripTrailing() + "…";
    }

    private AiConversation owned(Long id, User user) {
        return conversations.findOwned(id, user.getId()).orElseThrow(AiConversationService::notFound);
    }

    /** The last turns of the conversation for the prompt, oldest first. */
    private List<PriorTurn> history(Long conversationId) {
        List<AiConversationTurn> latest = new ArrayList<>(
                turns.findLatest(conversationId, PageRequest.of(0, DocumentQnaService.PRIOR_TURNS)));
        Collections.reverse(latest);
        return latest.stream().map(turn -> new PriorTurn(turn.getQuestion(), read(turn).answer())).toList();
    }

    private Long save(Long userId, Long courseId, Long conversationId, String question,
                      DocumentAnswerResponse answer) {
        String json = write(answer);
        return transactions.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            AiConversation conversation;
            if (conversationId == null) {
                conversation = conversations.save(AiConversation.builder()
                        .user(users.getReferenceById(userId))
                        .course(courseId == null ? null : courses.getReferenceById(courseId))
                        .title(title(question))
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            } else {
                // Deleted by its owner in another tab while the AI answered: the answer is still returned, unsaved.
                conversation = conversations.findOwned(conversationId, userId).orElse(null);
                if (conversation == null) {
                    log.debug("Conversation {} was deleted while the AI answered; the turn is not saved",
                            conversationId);
                    return null;
                }
                conversation.setUpdatedAt(now);
            }
            turns.save(AiConversationTurn.builder()
                    .conversation(conversation)
                    .question(question.strip())
                    .answerJson(json)
                    .createdAt(now)
                    .build());
            return conversation.getId();
        });
    }

    private String write(DocumentAnswerResponse answer) {
        try {
            return objectMapper.writeValueAsString(answer);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize an AI answer", ex);
        }
    }

    private DocumentAnswerResponse read(AiConversationTurn turn) {
        try {
            return objectMapper.readValue(turn.getAnswerJson(), DocumentAnswerResponse.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            log.warn("Saved AI answer {} could not be read: {}", turn.getId(), ex.getMessage());
            return DocumentAnswerResponse.withoutSources("Ответ не удалось показать.");
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCodes.CONVERSATION_NOT_FOUND, "Conversation not found");
    }
}
