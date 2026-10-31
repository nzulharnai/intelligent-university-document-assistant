package com.university.docassistant.service;

import com.university.docassistant.dto.AdminDtos.AdminStatsResponse;
import com.university.docassistant.dto.AdminDtos.AuditLogResponse;
import com.university.docassistant.dto.AdminDtos.AdminUserResponse;
import com.university.docassistant.dto.AdminDtos.TeacherRequestResponse;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.entity.NotificationType;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.AuditLogRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskSubmissionRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.service.storage.StorageService;
import com.university.docassistant.util.LikePatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final CourseRepository courseRepository;
    private final TaskRepository taskRepository;
    private final TaskSubmissionRepository submissionRepository;
    private final AssignmentRepository assignmentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;
    private final StorageService storageService;
    private final AssignmentService assignmentService;
    private final NotificationService notificationService;

    /**
     * Users by id, a page at a time. {@code query} (2+ characters, case-insensitive) matches email or name;
     * {@code role} narrows to one role (e.g. teachers for "assign a course").
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> listUsers(String query, Role role, Pageable pageable) {
        Pageable byId = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("id"));
        String pattern = LikePatterns.contains(query);
        Page<User> users = pattern == null
                ? (role == null ? userRepository.findAll(byId) : userRepository.findByRole(role, byId))
                : (role == null ? userRepository.search(pattern, byId) : userRepository.searchByRole(role, pattern, byId));
        return PageResponse.of(users.map(AdminService::toResponse));
    }

    /**
     * The changed user's existing JWT carries the old role, so JwtAuthenticationFilter rejects it
     * and they have to log in again - the new role takes effect immediately. Course relations of the old role are
     * dropped so they cannot grant access under the new one: a former STUDENT leaves their courses (and their tasks),
     * a former TEACHER's courses are left without a teacher for ADMIN to reassign.
     */
    @Transactional
    public AdminUserResponse updateRole(Long id, Role role, String adminEmail) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.USER_NOT_FOUND, "User not found"));

        // An admin demoting themselves could leave the system without any admin.
        if (user.getEmail().equals(adminEmail) && role != Role.ROLE_ADMIN) {
            throw ApiException.badRequest(ErrorCodes.CANNOT_CHANGE_OWN_ROLE, "You cannot change your own role");
        }

        if (user.getRole() != role) {
            log.info("Admin {} changed role of {} from {} to {}", adminEmail, user.getEmail(), user.getRole(), role);
            if (user.getRole() == Role.ROLE_STUDENT) {
                taskRepository.deleteByUserId(user.getId());
                enrollmentRepository.deleteByStudentId(user.getId());
            } else if (user.getRole() == Role.ROLE_TEACHER) {
                courseRepository.detachTeacher(user.getId());
            }
            auditService.record(adminEmail, AuditService.ROLE_CHANGED,
                    user.getEmail() + ": " + user.getRole() + " -> " + role);
            user.setRole(role);
            if (role != Role.ROLE_STUDENT) {
                user.setTeacherRequested(false); // A pending request is settled by any promotion.
            }
            userRepository.save(user);
        }
        return toResponse(user);
    }

    /** Students waiting for the teacher role, oldest request first. */
    @Transactional(readOnly = true)
    public PageResponse<TeacherRequestResponse> teacherRequests(Pageable pageable) {
        Pageable oldestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by("createdAt", "id"));
        return PageResponse.of(userRepository.findByTeacherRequestedTrueAndRole(Role.ROLE_STUDENT, oldestFirst)
                .map(user -> new TeacherRequestResponse(user.getId(), user.getFullName(), user.getEmail(),
                        user.isEmailVerified(), user.getCreatedAt())));
    }

    /**
     * Grants the requested teacher role through {@link #updateRole}, so it has the same effects as any role change:
     * the student's courses and tasks are dropped and their current token stops working (they log in again).
     */
    @Transactional
    public AdminUserResponse approveTeacherRequest(Long id, String adminEmail) {
        User user = pendingTeacherRequest(id);
        AdminUserResponse response = updateRole(id, Role.ROLE_TEACHER, adminEmail);
        auditService.record(adminEmail, AuditService.TEACHER_REQUEST_APPROVED, user.getEmail());
        notificationService.notify(List.of(id), NotificationType.TEACHER_REQUEST_APPROVED,
                "Заявка на роль преподавателя одобрена",
                "Деканат подтвердил вашу роль преподавателя. Войдите заново, чтобы открылись возможности преподавателя.",
                "/courses");
        return response;
    }

    /** Closes the request; the account stays ROLE_STUDENT with all its courses. */
    @Transactional
    public AdminUserResponse rejectTeacherRequest(Long id, String adminEmail) {
        User user = pendingTeacherRequest(id);
        user.setTeacherRequested(false);
        userRepository.save(user);
        auditService.record(adminEmail, AuditService.TEACHER_REQUEST_REJECTED, user.getEmail());
        notificationService.notify(List.of(id), NotificationType.TEACHER_REQUEST_REJECTED,
                "Заявка на роль преподавателя отклонена",
                "Деканат отклонил заявку. Аккаунт остаётся студенческим со всеми вашими курсами.",
                "/dashboard");
        log.info("Admin {} rejected the teacher role request of {}", adminEmail, user.getEmail());
        return toResponse(user);
    }

    private User pendingTeacherRequest(Long id) {
        User user = userRepository.findById(id).orElseThrow(() -> ApiException.notFound(ErrorCodes.USER_NOT_FOUND, "User not found"));
        if (!user.isTeacherRequested() || user.getRole() != Role.ROLE_STUDENT) {
            throw ApiException.notFound(ErrorCodes.TEACHER_REQUEST_NOT_FOUND, "Teacher request not found");
        }
        return user;
    }

    /**
     * Deletes the user together with their documents (and the assignments found in them), enrollments and tasks:
     * DB rows first, then the files on disk.
     * Courses they taught are kept without a teacher.
     * Files are erased only after the transaction commits, so a rolled-back delete never loses content.
     */
    @Transactional
    public void deleteUser(Long userId, String adminEmail) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.USER_NOT_FOUND, "User not found"));

        if (user.getEmail().equals(adminEmail)) {
            throw ApiException.badRequest(ErrorCodes.CANNOT_DELETE_SELF, "You cannot delete your own account");
        }

        List<String> storedFileNames = new ArrayList<>(documentRepository.findStoredFileNamesByOwnerId(userId));
        // Their submission rows go with their tasks (ON DELETE CASCADE); the files are erased below.
        storedFileNames.addAll(submissionRepository.findStoredFileNamesByStudentId(userId));

        taskRepository.deleteByUserId(userId);
        // Other students' work on assignments from this user's documents is kept (closed), never deleted.
        assignmentService.releaseDocuments(documentRepository.findIdsByOwnerId(userId), adminEmail);
        taskRepository.deleteByAssignmentSourceDocumentOwnerId(userId);
        assignmentRepository.deleteBySourceDocumentOwnerId(userId);
        taskRepository.deleteByDocumentOwnerId(userId);
        documentRepository.deleteByOwnerId(userId);
        enrollmentRepository.deleteByStudentId(userId);
        courseRepository.detachTeacher(userId);
        userRepository.delete(user);
        auditService.record(adminEmail, AuditService.USER_DELETED,
                user.getEmail() + " with " + storedFileNames.size() + " document(s)");

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storedFileNames.forEach(storageService::delete);
            }
        });
        log.info("Admin {} deleted user {} with {} document(s)", adminEmail, user.getEmail(), storedFileNames.size());
    }

    @Transactional(readOnly = true)
    public AdminStatsResponse stats() {
        return new AdminStatsResponse(
                userRepository.count(),
                documentRepository.count(),
                documentRepository.sumFileSize(),
                courseRepository.count(),
                enrollmentRepository.count(),
                assignmentRepository.count());
    }

    /** The system log, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> logs(Pageable pageable) {
        return PageResponse.of(auditLogRepository.findAll(pageable).map(entry -> new AuditLogResponse(
                entry.getId(), entry.getCreatedAt(), entry.getActorEmail(), entry.getAction(), entry.getDetails())));
    }

    private static AdminUserResponse toResponse(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.isEmailVerified(),
                user.getCreatedAt());
    }
}
