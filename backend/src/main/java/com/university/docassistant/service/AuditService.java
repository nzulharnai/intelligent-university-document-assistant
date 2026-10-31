package com.university.docassistant.service;

import com.university.docassistant.entity.AuditLog;
import com.university.docassistant.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The admin's system log. Each event is written in its own transaction, so it is kept even when the caller's
 * transaction is read-only or rolls back (a denied request), and a failed write never fails the request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String LOGIN_LOCKED = "LOGIN_LOCKED";
    public static final String DOCUMENT_UPLOADED = "DOCUMENT_UPLOADED";
    public static final String DOCUMENT_DELETED = "DOCUMENT_DELETED";
    public static final String DOCUMENT_CLASSIFIED = "DOCUMENT_CLASSIFIED";
    public static final String BATCH_IMPORTED = "BATCH_IMPORTED";
    public static final String COURSE_CREATED = "COURSE_CREATED";
    public static final String COURSE_TEACHER_CHANGED = "COURSE_TEACHER_CHANGED";
    public static final String STUDENT_ENROLLED = "STUDENT_ENROLLED";
    public static final String STUDENT_UNENROLLED = "STUDENT_UNENROLLED";
    public static final String COURSE_JOIN_FAILED = "COURSE_JOIN_FAILED";
    public static final String COURSE_INVITE_REGENERATED = "COURSE_INVITE_REGENERATED";
    public static final String ASSIGNMENT_CREATED = "ASSIGNMENT_CREATED";
    public static final String ASSIGNMENT_DELETED = "ASSIGNMENT_DELETED";
    public static final String ASSIGNMENT_CLOSED = "ASSIGNMENT_CLOSED";
    public static final String ASSIGNMENT_PUBLISHED = "ASSIGNMENT_PUBLISHED";
    public static final String ASSIGNMENT_REOPENED = "ASSIGNMENT_REOPENED";
    public static final String ASSIGNMENT_DEADLINE_CHANGED = "ASSIGNMENT_DEADLINE_CHANGED";
    public static final String PII_ORIGINAL_VIEWED = "PII_ORIGINAL_VIEWED";
    public static final String PII_ORIGINAL_ALLOWED = "PII_ORIGINAL_ALLOWED";
    public static final String PII_ORIGINAL_DENIED = "PII_ORIGINAL_DENIED";
    public static final String DOCUMENT_CATEGORY_SET = "DOCUMENT_CATEGORY_SET";
    public static final String OCR_RETRY_REQUESTED = "OCR_RETRY_REQUESTED";
    public static final String TASK_SUBMITTED = "TASK_SUBMITTED";
    /** A grade was given or changed; details carry the old and the new score. */
    public static final String TASK_GRADED = "TASK_GRADED";
    public static final String TASK_UNGRADED = "TASK_UNGRADED";
    /** ADMIN ran the deadline reminders by hand (POST /admin/notifications/run-reminders). */
    public static final String REMINDERS_RUN = "REMINDERS_RUN";
    public static final String ROLE_CHANGED = "ROLE_CHANGED";
    public static final String TEACHER_REQUEST_APPROVED = "TEACHER_REQUEST_APPROVED";
    public static final String TEACHER_REQUEST_REJECTED = "TEACHER_REQUEST_REJECTED";
    public static final String USER_DELETED = "USER_DELETED";
    public static final String PROFILE_UPDATED = "PROFILE_UPDATED";

    private static final int MAX_DETAILS_LENGTH = 1_000;

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actorEmail, String action, String details) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .createdAt(LocalDateTime.now())
                    .actorEmail(actorEmail)
                    .action(action)
                    .details(details == null || details.length() <= MAX_DETAILS_LENGTH
                            ? details : details.substring(0, MAX_DETAILS_LENGTH))
                    .build());
        } catch (RuntimeException ex) {
            log.warn("Could not write audit event {} of {}: {}", action, actorEmail, ex.toString());
        }
    }
}
