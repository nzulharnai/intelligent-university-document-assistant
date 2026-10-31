package com.university.docassistant.controller;

import com.university.docassistant.dto.AdminDtos.AdminStatsResponse;
import com.university.docassistant.dto.AdminDtos.AdminUserResponse;
import com.university.docassistant.dto.AdminDtos.TeacherRequestResponse;
import com.university.docassistant.dto.AdminDtos.AuditLogResponse;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.dto.AdminDtos.UpdateRoleRequest;
import com.university.docassistant.entity.Role;
import com.university.docassistant.service.AdminService;
import com.university.docassistant.service.DeadlineReminderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final DeadlineReminderService deadlineReminderService;

    /** How many deadline reminders a manual run sent. */
    public record RemindersRunResponse(int sent) {
    }

    /** Runs the deadline reminders now instead of waiting for the schedule (demo, or after an outage); audited. */
    @PostMapping("/notifications/run-reminders")
    public RemindersRunResponse runReminders(@AuthenticationPrincipal UserDetails principal) {
        return new RemindersRunResponse(deadlineReminderService.runNow(principal.getUsername()));
    }

    /** Users by id, paged; {@code q} searches email or name (2+ characters), {@code role} filters by role. */
    @GetMapping("/users")
    public PageResponse<AdminUserResponse> users(@RequestParam(value = "q", required = false) String query,
                                                 @RequestParam(value = "role", required = false) Role role,
                                                 @RequestParam(value = "page", defaultValue = "0") int page,
                                                 @RequestParam(value = "size", defaultValue = "20") int size) {
        return adminService.listUsers(query, role, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))));
    }

    @PatchMapping("/users/{id}/role")
    public AdminUserResponse updateRole(@PathVariable Long id,
                                        @Valid @RequestBody UpdateRoleRequest request,
                                        @AuthenticationPrincipal UserDetails principal) {
        return adminService.updateRole(id, request.role(), principal.getUsername());
    }

    /** Students who asked for the teacher role at registration, oldest first. */
    @GetMapping("/teacher-requests")
    public PageResponse<TeacherRequestResponse> teacherRequests(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return adminService.teacherRequests(PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))));
    }

    @PostMapping("/teacher-requests/{id}/approve")
    public AdminUserResponse approveTeacherRequest(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return adminService.approveTeacherRequest(id, principal.getUsername());
    }

    @PostMapping("/teacher-requests/{id}/reject")
    public AdminUserResponse rejectTeacherRequest(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return adminService.rejectTeacherRequest(id, principal.getUsername());
    }

    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        adminService.deleteUser(id, principal.getUsername());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public AdminStatsResponse stats() {
        return adminService.stats();
    }

    /** System log, newest first. */
    @GetMapping("/logs")
    public PageResponse<AuditLogResponse> logs(@RequestParam(value = "page", defaultValue = "0") int page,
                                               @RequestParam(value = "size", defaultValue = "50") int size) {
        return adminService.logs(PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)),
                Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }
}
