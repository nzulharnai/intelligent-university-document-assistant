package com.university.docassistant.controller;

import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.AssignmentDtos.CreateAssignmentRequest;
import com.university.docassistant.dto.AssignmentDtos.UpdateAssignmentRequest;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.service.AssignmentService;
import com.university.docassistant.util.SortValidator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AssignmentController {

    static final Set<String> SORTABLE_FIELDS = Set.of("deadline", "title", "createdAt", "id");

    private final AssignmentService assignmentService;

    /** The course's assignments; for its students, its teacher and ADMIN. */
    @GetMapping("/courses/{courseId}/assignments")
    public PageResponse<AssignmentResponse> list(
            @PathVariable Long courseId,
            @PageableDefault(size = 20, sort = "deadline", direction = Sort.Direction.ASC) Pageable pageable,
            @AuthenticationPrincipal UserDetails principal) {
        return assignmentService.list(courseId, principal.getUsername(),
                SortValidator.requireSortable(pageable, SORTABLE_FIELDS));
    }

    /** Creates an assignment and hands it out to every enrolled student; for the course teacher or ADMIN. */
    @PostMapping("/courses/{courseId}/assignments")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<AssignmentResponse> create(@PathVariable Long courseId,
                                                     @Valid @RequestBody CreateAssignmentRequest request,
                                                     @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(assignmentService.create(courseId, request, principal.getUsername()));
    }

    /** Fixes title, requirements ("description"), deadline, late work and max score; ACTIVE or CLOSED. MANAGE_ASSIGNMENTS. */
    @PatchMapping("/assignments/{id}")
    public AssignmentResponse update(@PathVariable Long id, @Valid @RequestBody UpdateAssignmentRequest request,
                                     @AuthenticationPrincipal UserDetails principal) {
        return assignmentService.update(id, request, principal.getUsername());
    }

    /** CLOSED -> ACTIVE; students who lack the task get it. MANAGE_ASSIGNMENTS on the course. */
    @PostMapping("/assignments/{id}/reopen")
    public AssignmentResponse reopen(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return assignmentService.reopen(id, principal.getUsername());
    }

    /** Closes the assignment (kept with its students' work, no new submissions); MANAGE_ASSIGNMENTS on the course. */
    @PostMapping("/assignments/{id}/close")
    public AssignmentResponse close(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return assignmentService.close(id, principal.getUsername());
    }

    /** 409 ASSIGNMENT_HAS_ACTIVITY when students already worked on it: close it instead. */
    @DeleteMapping("/assignments/{id}")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        assignmentService.delete(id, principal.getUsername());
        return ResponseEntity.noContent().build();
    }
}
