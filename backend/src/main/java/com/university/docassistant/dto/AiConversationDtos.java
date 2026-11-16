package com.university.docassistant.dto;

import java.time.LocalDateTime;
import java.util.List;

/** US-14: saved conversations with the AI assistant. */
public final class AiConversationDtos {

    private AiConversationDtos() {
    }

    /**
     * POST /ai/ask: the answer (the same fields as DocumentAnswerResponse) and the saved conversation it belongs to.
     * The next question of the same conversation sends {@code conversationId} back.
     */
    public record ConversationAnswerResponse(
            Long conversationId,
            String answer,
            boolean hasAnswer,
            List<SourceSnippetDto> sources,
            List<AnswerAssignmentDto> assignments,
            List<AnswerTaskDto> tasks,
            boolean citedExplicitly
    ) {
        public static ConversationAnswerResponse of(Long conversationId, DocumentAnswerResponse answer) {
            return new ConversationAnswerResponse(conversationId, answer.answer(), answer.hasAnswer(),
                    answer.sources(), answer.assignments(), answer.tasks(), answer.citedExplicitly());
        }
    }

    /**
     * GET /ai/conversations/{id}: the conversation and its latest questions in reading order (at most
     * AiConversationService.MAX_TURNS). {@code courseId} null: "Все мои курсы".
     */
    public record ConversationDetail(
            Long id,
            String title,
            Long courseId,
            String courseTitle,
            String courseCode,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<TurnView> turns
    ) {
    }

    /** One question and the answer as it was shown then (sources, assignments and tasks included). */
    public record TurnView(Long id, String question, LocalDateTime createdAt, DocumentAnswerResponse answer) {
    }

    /** GET /ai/limits: questions left in the current hour and day; -1 when that limit is off. */
    public record LimitsResponse(int remainingThisHour, int remainingToday, int perHour, int perDay) {
    }
}
