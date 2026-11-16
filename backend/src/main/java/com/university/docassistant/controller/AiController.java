package com.university.docassistant.controller;

import com.university.docassistant.dto.AiConversationDtos.ConversationAnswerResponse;
import com.university.docassistant.dto.AiConversationDtos.ConversationDetail;
import com.university.docassistant.dto.AiConversationDtos.LimitsResponse;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.repository.AiConversationRepository.ConversationRow;
import com.university.docassistant.service.AiClientService;
import com.university.docassistant.service.AiConversationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiClientService aiClientService;
    private final AiConversationService conversationService;

    /**
     * Lets the UI disable AI features up front instead of showing an error after the first question.
     * {@code available} only says the AI is configured; a call can still fail with 503 if the provider is down.
     */
    public record AiStatusResponse(boolean available, boolean enabled) {
    }

    /**
     * Without {@code courseId} the question is answered from all of the user's courses. {@code conversationId}
     * continues one of the user's saved conversations, in that conversation's scope (US-14).
     */
    public record AskRequest(@NotBlank @Size(max = 1_000) String question, Long courseId, Long conversationId) {
    }

    @GetMapping("/status")
    public AiStatusResponse status() {
        return new AiStatusResponse(aiClientService.isConfigured(), aiClientService.isEnabled());
    }

    /**
     * Answers only from documents of courses the user may access (403 for a course that is not theirs) and saves the
     * question and the answer in a conversation; 429 AI_QUESTION_LIMIT over the user's question limits.
     */
    @PostMapping("/ask")
    public ConversationAnswerResponse ask(@Valid @RequestBody AskRequest request,
                                          @AuthenticationPrincipal UserDetails principal) {
        return conversationService.ask(request.question(), request.courseId(), request.conversationId(),
                principal.getUsername());
    }

    /** US-14 "История": the user's own saved conversations, the latest first. */
    @GetMapping("/conversations")
    public PageResponse<ConversationRow> conversations(@PageableDefault(size = 20) Pageable pageable,
                                                       @AuthenticationPrincipal UserDetails principal) {
        return conversationService.list(principal.getUsername(), pageable);
    }

    @GetMapping("/conversations/{id}")
    public ConversationDetail conversation(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return conversationService.get(id, principal.getUsername());
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(@PathVariable Long id,
                                                   @AuthenticationPrincipal UserDetails principal) {
        conversationService.delete(id, principal.getUsername());
        return ResponseEntity.noContent().build();
    }

    /** US-14 AC-5: how many questions the user has left in this hour and today. */
    @GetMapping("/limits")
    public LimitsResponse limits(@AuthenticationPrincipal UserDetails principal) {
        return conversationService.limits(principal.getUsername());
    }
}
