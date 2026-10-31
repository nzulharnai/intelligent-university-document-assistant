package com.university.docassistant.controller;

import com.university.docassistant.dto.NotificationDtos.NotificationResponse;
import com.university.docassistant.dto.NotificationDtos.ReadAllResponse;
import com.university.docassistant.dto.NotificationDtos.UnreadCountResponse;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The current user's own notifications (US-13); any role. */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** Newest first. */
    @GetMapping
    public PageResponse<NotificationResponse> list(@RequestParam(value = "page", defaultValue = "0") int page,
                                                   @RequestParam(value = "size", defaultValue = "20") int size,
                                                   @AuthenticationPrincipal UserDetails principal) {
        return notificationService.listMine(principal.getUsername(),
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50))));
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal UserDetails principal) {
        return new UnreadCountResponse(notificationService.unreadCount(principal.getUsername()));
    }

    @PostMapping("/{id}/read")
    public NotificationResponse read(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return notificationService.markRead(id, principal.getUsername());
    }

    @PostMapping("/read-all")
    public ReadAllResponse readAll(@AuthenticationPrincipal UserDetails principal) {
        return notificationService.markAllRead(principal.getUsername());
    }
}
