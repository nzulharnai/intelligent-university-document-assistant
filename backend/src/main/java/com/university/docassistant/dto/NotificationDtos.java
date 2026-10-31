package com.university.docassistant.dto;

import com.university.docassistant.entity.NotificationType;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** The user's own notifications (US-13) and their e-mail preference. */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    /** Built directly by the JPQL constructor expressions in NotificationRepository; see {@link #SELECT}. */
    public record NotificationResponse(
            Long id,
            NotificationType type,
            String title,
            String body,
            /** Frontend path to open, or null. */
            String link,
            LocalDateTime createdAt,
            /** Null while unread. */
            LocalDateTime readAt
    ) {
        public static final String SELECT = """
                SELECT new com.university.docassistant.dto.NotificationDtos$NotificationResponse(
                    n.id, n.type, n.title, n.body, n.link, n.createdAt, n.readAt)
                FROM Notification n
                """;
    }

    public record UnreadCountResponse(long count) {
    }

    /** How many notifications "Прочитать все" marked. */
    public record ReadAllResponse(int updated) {
    }

    /** PATCH /users/me/preferences. */
    public record PreferencesRequest(@NotNull Boolean emailNotifications) {
    }
}
