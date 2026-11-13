package com.university.docassistant.dto;

import java.util.List;

/**
 * Answer of the Q&A assistant. {@code hasAnswer} is false when the documents do not cover the question (or have no
 * text); {@code sources} and {@code assignments} are then empty.
 * <p>
 * {@code citedExplicitly}: the model cited at least one fragment ([n]) or assignment ([задание N]). Then
 * {@code sources} are only the cited fragments; otherwise the model answered from the whole context without saying
 * which part, and {@code sources} are all of it. {@code assignments} are only the cited ones.
 */
public record DocumentAnswerResponse(
        String answer,
        boolean hasAnswer,
        List<SourceSnippetDto> sources,
        List<AnswerAssignmentDto> assignments,
        boolean citedExplicitly
) {

    /** An answer without sources: no answer, or no text to answer from. */
    public static DocumentAnswerResponse withoutSources(String answer) {
        return new DocumentAnswerResponse(answer, false, List.of(), List.of(), false);
    }
}
