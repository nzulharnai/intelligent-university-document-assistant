package com.university.docassistant.service;

import com.university.docassistant.config.AsyncConfig;
import com.university.docassistant.dto.NotificationDtos.NotificationResponse;
import com.university.docassistant.dto.NotificationDtos.ReadAllResponse;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.entity.Notification;
import com.university.docassistant.entity.NotificationType;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.NotificationRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.repository.UserRepository.MailRecipient;
import com.university.docassistant.security.AccessControlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * US-13: notifications in the site (the bell) and by e-mail. {@link #notify} writes the rows in the caller's
 * transaction, so they exist exactly when the action that caused them does; the e-mails go out only after that
 * transaction commits, on the mail executor, to the users who kept e-mails on. A failed or refused e-mail is logged
 * and never undoes or fails the action. Reading is always the current user's own notifications.
 */
@Slf4j
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final AccessControlService access;
    private final EmailService emailService;
    private final TaskExecutor mailExecutor;
    private final Clock clock;
    private final String frontendUrl;

    public NotificationService(NotificationRepository notificationRepository, UserRepository userRepository,
                               AccessControlService access, EmailService emailService,
                               @Qualifier(AsyncConfig.MAIL_EXECUTOR) TaskExecutor mailExecutor, Clock clock,
                               @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.access = access;
        this.emailService = emailService;
        this.mailExecutor = mailExecutor;
        this.clock = clock;
        this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
    }

    /**
     * One notification per user (repeated ids count once). {@code link} is a frontend path such as "/dashboard".
     * Call it inside the transaction of the action; without one the rows are saved on their own.
     */
    @Transactional
    public void notify(Collection<Long> userIds, NotificationType type, String title, String body, String link) {
        List<Long> ids = List.copyOf(new LinkedHashSet<>(userIds));
        if (ids.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        notificationRepository.saveAll(ids.stream().map(id -> Notification.builder()
                .user(userRepository.getReferenceById(id)).type(type).title(title).body(body).link(link)
                .createdAt(now).build()).toList());
        List<MailRecipient> recipients = userRepository.findMailRecipients(ids);
        log.info("Notification {} for {} user(s), {} by e-mail", type, ids.size(), recipients.size());
        if (!recipients.isEmpty()) {
            afterCommit(() -> mail(recipients, type, title, body, link));
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> listMine(String email, Pageable pageable) {
        User user = access.currentUser(email);
        return PageResponse.of(notificationRepository.findViewsByUserId(user.getId(), pageable));
    }

    @Transactional(readOnly = true)
    public long unreadCount(String email) {
        return notificationRepository.countUnread(access.currentUser(email).getId());
    }

    /** Marks one of the user's own notifications read; 404 for any other id, so others' ids reveal nothing. */
    @Transactional
    public NotificationResponse markRead(Long id, String email) {
        User user = access.currentUser(email);
        notificationRepository.markRead(id, user.getId(), LocalDateTime.now(clock));
        return notificationRepository.findViewByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOTIFICATION_NOT_FOUND, "Notification not found"));
    }

    @Transactional
    public ReadAllResponse markAllRead(String email) {
        User user = access.currentUser(email);
        return new ReadAllResponse(notificationRepository.markAllRead(user.getId(), LocalDateTime.now(clock)));
    }

    /** Hands the e-mails to the mail executor; a full queue or a failed e-mail is only logged. */
    private void mail(List<MailRecipient> recipients, NotificationType type, String title, String body, String link) {
        String url = frontendUrl + (link == null ? "/dashboard" : link);
        try {
            mailExecutor.execute(() -> recipients.forEach(recipient -> {
                try {
                    emailService.sendNotification(recipient.email(), recipient.fullName(), title, body, url);
                } catch (RuntimeException ex) {
                    log.warn("Notification e-mail {} to user {} failed: {}", type, recipient.id(), ex.toString());
                }
            }));
        } catch (TaskRejectedException ex) {
            log.warn("Notification e-mails {} for {} user(s) dropped: mail queue full", type, recipients.size());
        }
    }

    /** Runs {@code action} once the transaction has committed (at once when there is none). */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
