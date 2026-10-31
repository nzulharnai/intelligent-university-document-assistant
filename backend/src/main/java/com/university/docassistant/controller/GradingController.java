package com.university.docassistant.controller;

import com.university.docassistant.dto.GradingDtos.GradeRequest;
import com.university.docassistant.dto.GradingDtos.GradingFilter;
import com.university.docassistant.dto.GradingDtos.GradingResponse;
import com.university.docassistant.dto.GradingDtos.GradingStudent;
import com.university.docassistant.service.GradingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** US-11: grading students' work; for the course's teacher or ADMIN (decided in GradingService). */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GradingController {

    /** A whole course fits: the page grows by "Показать ещё" instead of paging. */
    static final int MAX_PAGE_SIZE = 500;

    private final GradingService gradingService;

    @PutMapping("/tasks/{id}/grade")
    public GradingStudent grade(@PathVariable Long id, @Valid @RequestBody GradeRequest request,
                                @AuthenticationPrincipal UserDetails principal) {
        return gradingService.grade(id, request, principal.getUsername());
    }

    @DeleteMapping("/tasks/{id}/grade")
    public GradingStudent ungrade(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return gradingService.ungrade(id, principal.getUsername());
    }

    /** Enrolled students by name, with state, current hand-in and grade; {@code state} filters them. */
    @GetMapping("/assignments/{id}/grading")
    public GradingResponse grading(@PathVariable Long id,
                                   @RequestParam(value = "state", defaultValue = "ALL") GradingFilter state,
                                   @RequestParam(value = "page", defaultValue = "0") int page,
                                   @RequestParam(value = "size", defaultValue = "50") int size,
                                   @AuthenticationPrincipal UserDetails principal) {
        return gradingService.list(id, state,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE))), principal.getUsername());
    }
}
