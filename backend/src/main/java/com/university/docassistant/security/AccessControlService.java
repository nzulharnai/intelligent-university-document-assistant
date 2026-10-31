package com.university.docassistant.security;

import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.OcrStatus;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository.TaskAccess;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The one place that decides access. The core rule: a STUDENT reaches only the courses they are enrolled in and
 * those courses' documents (not TEACHER_ONLY ones); a TEACHER manages only the courses they teach and the documents
 * in them; ADMIN manages everything. Every document has a course (documents.course_id is NOT NULL). Everything else - document
 * lists, search, Q&A retrieval, tasks - is filtered with {@link #scope(User)}, so it cannot drift from these checks.
 * Callers run inside a transaction (course teacher and document course are lazy).
 */
@Service
@RequiredArgsConstructor
public class AccessControlService {

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final DocumentRepository documentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final AuditService auditService;

    /**
     * What a user may reach. {@code allCourses} (ADMIN) lifts the course filter; otherwise only documents of
     * {@code courseIds} are reachable, and {@code includeTeacherOnly} says whether their TEACHER_ONLY documents - and
     * those hidden from students by {@link #isHiddenFromStudents} - are too. {@code courseIds} is empty when the user has no course.
     */
    public record Scope(boolean allCourses, List<Long> courseIds, boolean includeTeacherOnly) {

        public boolean isEmpty() {
            return !allCourses && courseIds.isEmpty();
        }

        /** For SQL "IN (:ids)": PostgreSQL rejects an empty list, and no course has id -1. */
        public List<Long> courseIdsParam() {
            return courseIds.isEmpty() ? List.of(-1L) : courseIds;
        }
    }

    /** Step 1: the user behind the JWT must still exist. */
    public User currentUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> ApiException.unauthorized(ErrorCodes.ACCOUNT_NOT_FOUND, "User no longer exists"));
    }

    /** Allowed course ids: enrolled ones for a STUDENT, taught ones for a TEACHER, all of them for ADMIN. */
    public Scope scope(User user) {
        return switch (user.getRole()) {
            case ROLE_ADMIN -> new Scope(true, courseRepository.findAllIds(), true);
            case ROLE_TEACHER -> new Scope(false, courseRepository.findIdsByTeacherId(user.getId()), true);
            case ROLE_STUDENT -> new Scope(false, enrollmentRepository.findCourseIdsByStudentId(user.getId()), false);
        };
    }

    /** The course if the user has {@code permission} on it: 404 when it does not exist, 403 when not allowed. */
    public Course authorizeCourse(User user, Long courseId, Permission permission) {
        Course course = courseRepository.findWithTeacherById(courseId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.COURSE_NOT_FOUND, "Course not found"));
        if (!Permission.granted(user.getRole(), permission) || !isMember(user, course)) {
            boolean student = user.getRole() == Role.ROLE_STUDENT;
            throw deny(user, permission + " on course " + courseId,
                    student ? ErrorCodes.COURSE_NOT_ENROLLED : ErrorCodes.COURSE_NOT_MANAGED,
                    student ? "You are not enrolled in this course" : "You do not manage this course");
        }
        return course;
    }

    /**
     * The document if the user has {@code permission} on it; 404 when it does not exist, 403 otherwise.
     * Steps: (1) the user exists - done by {@link #currentUser}; (2) the role; (3) the document; (4) its course;
     * (5) the user's relation to that course; (6) the permission; (7) the document's visibility - for a student also
     * that it is not hidden by {@link #isHiddenFromStudents}; (8) allow.
     */
    public Document authorizeDocument(User user, Long documentId, Permission permission) {
        if (user.getRole() == null) {                                                    // 2
            throw deny(user, permission + " on document " + documentId, ErrorCodes.ACCESS_DENIED, "Access denied");
        }
        Document document = documentRepository.findWithOwnerById(documentId)           // 3
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.DOCUMENT_NOT_FOUND, "Document not found"));
        if (!can(user, document, permission)) {                                          // 4-7
            throw deny(user, permission + " on document " + documentId, ErrorCodes.ACCESS_DENIED, "Access denied");
        }
        return document;                                                                 // 8
    }

    /**
     * A task is worked on only by its own user (403 TASK_NOT_OWN), and a task of a course only while that user is
     * still enrolled in it (403 COURSE_NOT_ENROLLED): a student who left keeps the task for the teacher, but cannot
     * work on it until they re-enroll. Ownership is the rule here rather than a role permission, since legacy tasks
     * without a course may belong to any role.
     */
    public void authorizeOwnTask(User user, TaskAccess task) {
        if (!task.userId().equals(user.getId())) {
            throw deny(user, "CHANGE task " + task.id(), ErrorCodes.TASK_NOT_OWN, "You can change only your own tasks");
        }
        if (task.courseId() != null && !enrollmentRepository.existsByStudentIdAndCourseId(user.getId(), task.courseId())) {
            throw deny(user, "CHANGE task " + task.id(), ErrorCodes.COURSE_NOT_ENROLLED, "You are not enrolled in this course");
        }
    }

    /** Same decision as {@link #authorizeCourse}, without throwing, for a course already loaded with its teacher. */
    public boolean can(User user, Course course, Permission permission) {
        return Permission.granted(user.getRole(), permission) && isMember(user, course);
    }

    /** Same decision as {@link #authorizeDocument}, without throwing; for "what may I do" flags in responses. */
    public boolean can(User user, Document document, Permission permission) {
        if (user.getRole() == Role.ROLE_ADMIN) {
            return true;                                         // System-level access, course-less documents too.
        }
        Course course = document.getCourse();                                            // 4
        if (course == null || !isMember(user, course)) {                                 // 5
            return false;
        }
        if (!Permission.granted(user.getRole(), permission)) {                           // 6
            return false;
        }
        return user.getRole() != Role.ROLE_STUDENT                                       // 7
                || (document.getVisibility() != DocumentVisibility.TEACHER_ONLY
                    && !isHiddenFromStudents(document.getAiStatus(), document.getOcrStatus()));
    }

    /**
     * Rule C: until the AI has classified it, a document may still turn out to be EXAM_ANSWERS - and so may one the AI
     * could not classify (FAILED) or a scan whose text could not be recognized (OCR FAILED, "Нужна проверка"). A type
     * the teacher set by hand (MANUAL) is a decision, so such a scan is visible again. The repository queries
     * (DocumentRepository, DocumentChunkRepository) repeat this rule in SQL.
     */
    public static boolean isHiddenFromStudents(AiStatus aiStatus, OcrStatus ocrStatus) {
        return aiStatus == AiStatus.PENDING || aiStatus == AiStatus.PROCESSING || aiStatus == AiStatus.FAILED
                || (ocrStatus == OcrStatus.FAILED && aiStatus != AiStatus.MANUAL);
    }

    /**
     * The view rule of {@link #can} for rows that carry only their course, visibility, AI and OCR status, checked
     * against a {@link #scope}: ADMIN sees everything; others only documents of their courses, and a student (no
     * includeTeacherOnly) neither TEACHER_ONLY ones nor ones {@link #isHiddenFromStudents}.
     */
    public static boolean canView(Scope scope, Long courseId, DocumentVisibility visibility, AiStatus aiStatus,
                                  OcrStatus ocrStatus) {
        if (scope.allCourses()) {
            return true;
        }
        if (courseId == null || !scope.courseIds().contains(courseId)) {
            return false;
        }
        return scope.includeTeacherOnly()
                || (visibility != DocumentVisibility.TEACHER_ONLY && !isHiddenFromStudents(aiStatus, ocrStatus));
    }

    /** Whether the user teaches the course (TEACHER) or is enrolled in it (STUDENT); ADMIN always. */
    public boolean isMember(User user, Course course) {
        return switch (user.getRole()) {
            case ROLE_ADMIN -> true;
            case ROLE_TEACHER -> isTeacherOf(user, course);
            case ROLE_STUDENT -> enrollmentRepository.existsByStudentIdAndCourseId(user.getId(), course.getId());
        };
    }

    private ApiException deny(User user, String attempt, String code, String message) {
        auditService.record(user.getEmail(), AuditService.ACCESS_DENIED, attempt);
        return ApiException.forbidden(code, message);
    }

    public static boolean isTeacherOf(User user, Course course) {
        return course.getTeacher() != null && course.getTeacher().getId().equals(user.getId());
    }
}
