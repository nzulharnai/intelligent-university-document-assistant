package com.university.docassistant.service;

import com.university.docassistant.dto.CourseDtos.CourseDetailsResponse;
import com.university.docassistant.dto.CourseDtos.CourseResponse;
import com.university.docassistant.dto.CourseDtos.CreateCourseRequest;
import com.university.docassistant.dto.CourseDtos.StudentResponse;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.EnrollmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.UserRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.JoinAttemptLimiter;
import com.university.docassistant.security.Permission;
import com.university.docassistant.util.InviteCodes;
import com.university.docassistant.util.LikePatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * "My courses": the enrolled ones for a STUDENT, the taught ones for a TEACHER, all of them for ADMIN.
 * A course page is open to its members only (403 otherwise). Enrollment: a STUDENT can enroll themselves through
 * the course's invitation link; the course's teacher or ADMIN can enroll or remove any student. Enrolling hands the
 * course's assignments out to the student as tasks; leaving removes them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final AssignmentService assignmentService;
    private final AccessControlService access;
    private final AuditService auditService;
    private final JoinAttemptLimiter joinAttemptLimiter;
    private final CoursePrivacyRemask privacyRemask;

    static final String COURSE_NOT_FOUND = "Курс не найден";
    static final String TEACHER_ROLE_PENDING_MESSAGE =
            "Заявка на роль преподавателя ещё не подтверждена деканатом. До подтверждения вы работаете как студент.";
    static final String ONLY_STUDENTS_JOIN = "Присоединиться к курсу по ссылке могут только студенты";
    static final String JOIN_BY_LINK_ONLY = "Записаться на курс можно только по ссылке-приглашению преподавателя";

    @Transactional
    public CourseResponse create(CreateCourseRequest request, String email) {
        User user = access.currentUser(email);
        if (!Permission.granted(user.getRole(), Permission.MANAGE_COURSE)) {
            if (user.isTeacherRequested()) {
                throw ApiException.forbidden(ErrorCodes.TEACHER_ROLE_PENDING, TEACHER_ROLE_PENDING_MESSAGE);
            }
            throw ApiException.forbidden(ErrorCodes.COURSE_CREATE_FORBIDDEN, "Only TEACHER or ADMIN can create courses");
        }
        User teacher = user;
        if (request.teacherId() != null && !request.teacherId().equals(user.getId())) {
            if (user.getRole() != Role.ROLE_ADMIN) {
                throw ApiException.forbidden(ErrorCodes.ADMIN_ONLY, "Only ADMIN can create a course for another teacher");
            }
            teacher = requireTeacher(request.teacherId());
        }
        Course course = courseRepository.save(Course.builder()
                .title(request.title().trim())
                .code(StringUtils.hasText(request.code()) ? request.code().trim().toUpperCase() : null)
                .description(request.description())
                .teacher(teacher)
                .build());
        auditService.record(user.getEmail(), AuditService.COURSE_CREATED,
                "Course " + course.getId() + " \"" + course.getTitle() + "\" taught by " + teacher.getEmail());
        log.info("User {} created course {} ({}) taught by {}", user.getEmail(), course.getId(), course.getCode(),
                teacher.getEmail());
        return toResponse(course);
    }

    @Transactional(readOnly = true)
    public PageResponse<CourseResponse> listMine(String email, String query, Pageable pageable) {
        User user = access.currentUser(email);
        // Title or code search within the user's own courses; terms under 2 characters list everything.
        String pattern = LikePatterns.contains(query);
        Page<Course> courses = pattern == null
                ? switch (user.getRole()) {
                    case ROLE_ADMIN -> courseRepository.findAllWithTeacher(pageable);
                    case ROLE_TEACHER -> courseRepository.findByTeacherId(user.getId(), pageable);
                    case ROLE_STUDENT -> courseRepository.findByStudentId(user.getId(), pageable);
                }
                : switch (user.getRole()) {
                    case ROLE_ADMIN -> courseRepository.searchAll(pattern, pageable);
                    case ROLE_TEACHER -> courseRepository.searchByTeacherId(user.getId(), pattern, pageable);
                    case ROLE_STUDENT -> courseRepository.searchByStudentId(user.getId(), pattern, pageable);
                };
        return PageResponse.of(courses.map(CourseService::toResponse));
    }

    /** The course page; 403 for a user who neither teaches it nor is enrolled in it (ADMIN always may). */
    @Transactional(readOnly = true)
    public CourseDetailsResponse details(Long courseId, String email) {
        User user = access.currentUser(email);
        Course course = access.authorizeCourse(user, courseId, Permission.VIEW_COURSE);
        return new CourseDetailsResponse(
                toResponse(course),
                enrollmentRepository.countByCourseId(courseId),
                user.getRole() == Role.ROLE_STUDENT,
                Permission.granted(user.getRole(), Permission.MANAGE_COURSE));
    }

    /** The course's students; for its teacher and ADMIN. */
    @Transactional(readOnly = true)
    public PageResponse<StudentResponse> students(Long courseId, String email, Pageable pageable) {
        access.authorizeCourse(access.currentUser(email), courseId, Permission.MANAGE_COURSE);
        return PageResponse.of(enrollmentRepository.findStudentsByCourseId(courseId, pageable)
                .map(student -> new StudentResponse(student.getId(), student.getFullName(), student.getEmail())));
    }

    /**
     * The course's teacher or ADMIN enrolls a specific student. Students cannot enroll themselves here: course ids
     * are sequential, so they join only through the invitation code ({@link #join}).
     */
    @Transactional
    public void enroll(Long courseId, Long studentId, String email) {
        User user = access.currentUser(email);
        if (studentId == null || studentId.equals(user.getId())) {
            if (user.getRole() == Role.ROLE_STUDENT) {
                auditService.record(user.getEmail(), AuditService.ACCESS_DENIED, "self-enroll by id in course " + courseId);
                throw ApiException.forbidden(ErrorCodes.ENROLL_BY_LINK_ONLY, JOIN_BY_LINK_ONLY);
            }
            throw ApiException.badRequest(ErrorCodes.NOT_A_STUDENT, "Only students can enroll in a course");
        }
        Course course = courseRepository.findWithTeacherById(courseId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.COURSE_NOT_FOUND, "Course not found"));

        access.authorizeCourse(user, course.getId(), Permission.MANAGE_COURSE);
        User student = userRepository.findById(studentId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.STUDENT_NOT_FOUND, "Student not found"));
        if (student.getRole() != Role.ROLE_STUDENT) {
            throw ApiException.badRequest(ErrorCodes.NOT_A_STUDENT, "Only students can be enrolled in a course");
        }

        enrollStudent(user, course, student);
    }

    /**
     * A student joins the course behind an invitation code. Joining twice is not an error. A wrong code gives the
     * same 404 whether or not some course exists, is audited, and counts towards {@link JoinAttemptLimiter}
     * (429 once exceeded). Returns the course id.
     */
    @Transactional
    public Long join(String code, String email) {
        User user = access.currentUser(email);
        if (user.getRole() != Role.ROLE_STUDENT) {
            throw ApiException.forbidden(ErrorCodes.NOT_A_STUDENT, ONLY_STUDENTS_JOIN);
        }
        long blockedFor = joinAttemptLimiter.blockedForSeconds(user.getId());
        if (blockedFor > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Слишком много попыток с неверным кодом. "
                    + "Попробуйте через " + Math.max(1, (blockedFor + 59) / 60) + " мин.", ErrorCodes.TOO_MANY_ATTEMPTS, blockedFor);
        }
        String candidate = code == null ? "" : code.strip();
        Optional<Course> course = InviteCodes.FORMAT.matcher(candidate).matches()
                ? courseRepository.findByInviteCode(candidate)
                : Optional.empty();
        if (course.isEmpty()) {
            joinAttemptLimiter.recordFailure(user.getId());
            // Only a prefix: a mistyped code may be close to a real one.
            auditService.record(user.getEmail(), AuditService.COURSE_JOIN_FAILED, "invalid invitation code \""
                    + (candidate.length() > 4 ? candidate.substring(0, 4) + "…" : candidate) + "\"");
            throw ApiException.notFound(ErrorCodes.INVITE_INVALID, COURSE_NOT_FOUND);
        }
        enrollStudent(user, course.get(), user);
        return course.get().getId();
    }

    /** The invitation code of the course; for its teacher and ADMIN. */
    @Transactional(readOnly = true)
    public String inviteCode(Long courseId, String email) {
        return access.authorizeCourse(access.currentUser(email), courseId, Permission.MANAGE_COURSE).getInviteCode();
    }

    /** Replaces the invitation code; the old link stops working at once. For the course's teacher and ADMIN. */
    @Transactional
    public String regenerateInviteCode(Long courseId, String email) {
        User user = access.currentUser(email);
        Course course = access.authorizeCourse(user, courseId, Permission.MANAGE_COURSE);
        course.setInviteCode(InviteCodes.generate());
        courseRepository.save(course);
        auditService.record(user.getEmail(), AuditService.COURSE_INVITE_REGENERATED, "Course " + courseId);
        log.info("User {} regenerated the invitation link of course {}", user.getEmail(), courseId);
        return course.getInviteCode();
    }

    /** Idempotent: an already enrolled student is left as is. New students get the course's tasks. */
    private void enrollStudent(User actor, Course course, User student) {
        if (enrollmentRepository.enroll(course.getId(), student.getId()) > 0) {
            int tasks = assignmentService.handOut(course.getId());
            auditService.record(actor.getEmail(), AuditService.STUDENT_ENROLLED,
                    student.getEmail() + " in course " + course.getId());
            log.info("User {} enrolled {} in course {} ({} task(s) handed out)",
                    actor.getEmail(), student.getEmail(), course.getId(), tasks);
        }
    }

    /**
     * Removes a student from the course; for the student themselves, the teacher or ADMIN. Their tasks and submissions
     * are kept (the teacher and ADMIN still read them); the student just loses access to the course and its tasks.
     * Enrolling again gives back the same tasks: fanOut only adds the ones they do not have.
     */
    @Transactional
    public void unenroll(Long courseId, Long studentId, String email) {
        User user = access.currentUser(email);
        if (!studentId.equals(user.getId())) {
            access.authorizeCourse(user, courseId, Permission.MANAGE_COURSE);
        } else if (!courseRepository.existsById(courseId)) {
            throw ApiException.notFound(ErrorCodes.COURSE_NOT_FOUND, "Course not found");
        }
        if (enrollmentRepository.unenroll(courseId, studentId) > 0) {
            auditService.record(user.getEmail(), AuditService.STUDENT_UNENROLLED,
                    "Student " + studentId + " from course " + courseId);
            log.info("User {} removed student {} from course {}", user.getEmail(), studentId, courseId);
        }
    }

    /** ADMIN hands a course over to another teacher. */
    @Transactional
    public CourseResponse assignTeacher(Long courseId, Long teacherId, String email) {
        User admin = access.currentUser(email);
        if (!Permission.granted(admin.getRole(), Permission.MANAGE_SYSTEM)) {
            throw ApiException.forbidden(ErrorCodes.ADMIN_ONLY, "Only ADMIN can change the teacher of a course");
        }
        Course course = courseRepository.findWithTeacherById(courseId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.COURSE_NOT_FOUND, "Course not found"));
        User previous = course.getTeacher();
        course.setTeacher(requireTeacher(teacherId));
        if (previous == null || !previous.getId().equals(teacherId)) {
            // The teacher's e-mail and phone stay readable in the course's documents: those are now someone else's.
            privacyRemask.schedule(List.of(courseId));
        }
        auditService.record(admin.getEmail(), AuditService.COURSE_TEACHER_CHANGED,
                "Course " + courseId + " now taught by " + course.getTeacher().getEmail());
        log.info("Admin {} assigned teacher {} to course {}", admin.getEmail(), teacherId, courseId);
        return toResponse(course);
    }

    private User requireTeacher(Long teacherId) {
        User teacher = userRepository.findById(teacherId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.TEACHER_NOT_FOUND, "Teacher not found"));
        if (teacher.getRole() != Role.ROLE_TEACHER) {
            throw ApiException.badRequest(ErrorCodes.NOT_A_TEACHER, "The user is not a TEACHER");
        }
        return teacher;
    }

    private static CourseResponse toResponse(Course course) {
        User teacher = course.getTeacher();
        return new CourseResponse(
                course.getId(),
                course.getTitle(),
                course.getCode(),
                course.getDescription(),
                teacher != null ? teacher.getId() : null,
                teacher != null ? teacher.getFullName() : null,
                teacher != null ? teacher.getEmail() : null);
    }
}
