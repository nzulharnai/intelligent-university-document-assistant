package com.university.docassistant.controller;

import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.dto.TaskDtos.TaskStateFilter;
import com.university.docassistant.dto.TaskDtos.TaskSummary;
import com.university.docassistant.dto.TaskDtos.UpdateStatusRequest;
import com.university.docassistant.dto.TaskResponse;
import com.university.docassistant.service.TaskService;
import com.university.docassistant.util.SortValidator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    static final Set<String> SORTABLE_FIELDS = Set.of("deadline", "status", "title", "id");

    private final TaskService taskService;

    /**
     * The current user's own tasks, nearest deadline first (tasks without a deadline last); optional {@code state}
     * filter (TaskStateFilter) and {@code courseId}.
     */
    @GetMapping("/my")
    public PageResponse<TaskResponse> my(@RequestParam(value = "state", defaultValue = "ALL") TaskStateFilter state,
                                         @RequestParam(value = "courseId", required = false) Long courseId,
                                         @PageableDefault(size = 20, sort = {"deadline", "id"}, direction = Sort.Direction.ASC)
                                         Pageable pageable,
                                         @AuthenticationPrincipal UserDetails principal) {
        return taskService.listMine(principal.getUsername(), state, courseId,
                SortValidator.requireSortable(pageable, SORTABLE_FIELDS));
    }

    /** "К сдаче: N · Просрочено: M · Оценено: K" over the current user's tasks. */
    @GetMapping("/my/summary")
    public TaskSummary summary(@AuthenticationPrincipal UserDetails principal) {
        return taskService.summary(principal.getUsername());
    }

    /** "Upcoming deadlines": unfinished tasks due from now on, nearest first. */
    @GetMapping("/upcoming")
    public List<TaskResponse> upcoming(@RequestParam(value = "limit", defaultValue = "5") int limit,
                                       @AuthenticationPrincipal UserDetails principal) {
        return taskService.upcoming(principal.getUsername(), Math.max(1, Math.min(limit, 20)));
    }

    @PutMapping("/{id}/status")
    public TaskResponse updateStatus(@PathVariable Long id,
                                     @Valid @RequestBody UpdateStatusRequest request,
                                     @AuthenticationPrincipal UserDetails principal) {
        return taskService.updateStatus(id, request.status(), principal.getUsername());
    }
}
