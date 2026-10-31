package com.university.docassistant.service;

import com.university.docassistant.dto.SubmissionDtos.SubmissionResponse;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.TaskSubmission;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskRepository.SubmitRules;
import com.university.docassistant.repository.TaskRepository.TaskAccess;
import com.university.docassistant.repository.TaskSubmissionRepository;
import com.university.docassistant.repository.TaskSubmissionRepository.SubmissionAccess;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import com.university.docassistant.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Students hand in their work for a task (Moodle-style submission); this is the only upload a STUDENT may make.
 * Submit: the task's own student, while still enrolled in its course, and only for tasks that come from a course
 * assignment. Read: that student, the course's teacher, or ADMIN. Submissions never become course documents, so
 * they stay out of document lists, search and Q&A retrieval. A submission makes the task SUBMITTED, with the
 * submission time (app time zone) and whether it came after the deadline; the student cannot set that by hand.
 * Accepted while the assignment is ACTIVE and the task not GRADED; after the deadline only if the assignment allows
 * late work. Each hand-in is a new version; earlier versions stay as history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionService {

    static final int MAX_FILES_PER_SUBMISSION = 10;
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TaskSubmissionRepository submissionRepository;
    private final TaskRepository taskRepository;
    private final AssignmentRepository assignmentRepository;
    private final CourseRepository courseRepository;
    private final AccessControlService access;
    private final StorageService storageService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public record SubmissionContent(SubmissionAccess submission, Resource content) {
    }

    /**
     * Stores the files as one submission: the task's next version. All-or-nothing: an unsupported, empty or oversized
     * file rejects the whole request, so a student never ends up with half a submission. Rights and the deadline
     * are checked before any file is read, and again under the task's row lock before anything is saved.
     */
    public List<SubmissionResponse> submit(Long taskId, List<MultipartFile> files, String email) {
        User student = access.currentUser(email);
        if (!Permission.granted(student.getRole(), Permission.SUBMIT_TASK)) {
            auditService.record(student.getEmail(), AuditService.ACCESS_DENIED, "SUBMIT_TASK on task " + taskId);
            throw ApiException.forbidden(ErrorCodes.NOT_A_STUDENT, "Сдавать решения заданий могут только студенты");
        }
        TaskAccess task = taskRepository.findAccessById(taskId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.TASK_NOT_FOUND, "Task not found"));
        if (!task.userId().equals(student.getId())) {
            auditService.record(student.getEmail(), AuditService.ACCESS_DENIED, "SUBMIT_TASK on task " + taskId);
            throw ApiException.forbidden(ErrorCodes.TASK_NOT_OWN, "Можно сдавать решения только к своим задачам");
        }
        if (task.courseId() == null || task.assignmentId() == null) {
            throw ApiException.badRequest(ErrorCodes.TASK_NOT_SUBMITTABLE, "Эта задача не относится к заданию курса, к ней нельзя прикрепить решение");
        }
        Course course = courseRepository.findById(task.courseId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.COURSE_NOT_FOUND, "Course not found"));
        if (!access.isMember(student, course)) {
            auditService.record(student.getEmail(), AuditService.ACCESS_DENIED, "SUBMIT_TASK on task " + taskId);
            throw ApiException.forbidden(ErrorCodes.COURSE_NOT_ENROLLED, "You are not enrolled in this course");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        checkAccepting(rulesOf(taskId), now);

        List<PreparedFile> prepared = prepare(files);
        List<String> storedFileNames = new ArrayList<>();
        Submitted result;
        try {
            List<TaskSubmission> rows = new ArrayList<>();
            for (PreparedFile file : prepared) {
                String storedFileName = storageService.store(file.content());
                storedFileNames.add(storedFileName);
                rows.add(TaskSubmission.builder()
                        .task(taskRepository.getReferenceById(taskId))
                        .student(student)
                        .originalFileName(file.fileName())
                        .storedFileName(storedFileName)
                        .contentType(file.contentType())
                        .fileSize((long) file.content().length)
                        .submittedAt(now)
                        .build());
            }
            // The rows and the task's new status commit together; the files are already stored (removed on failure).
            result = transactionTemplate.execute(status -> {
                taskRepository.lockById(taskId);
                boolean late = checkAccepting(rulesOf(taskId), now);
                int version = submissionRepository.findMaxVersion(taskId) + 1;
                rows.forEach(row -> row.setVersion(version));
                List<Long> saved = submissionRepository.saveAll(rows).stream().map(TaskSubmission::getId).toList();
                if (taskRepository.markSubmitted(taskId, now, late) == 0) {
                    throw alreadyGraded();
                }
                return new Submitted(saved, version, late);
            });
        } catch (RuntimeException ex) {
            storedFileNames.forEach(storageService::delete);
            throw ex;
        }
        auditService.record(student.getEmail(), AuditService.TASK_SUBMITTED, "version " + result.version() + ", "
                + result.ids().size() + " file(s) for task " + taskId + " (assignment " + task.assignmentId() + ")"
                + (result.late() ? ", late" : ""));
        log.info("Student {} submitted version {} ({} file(s){}) for task {} in course {}", student.getEmail(),
                result.version(), result.ids().size(), result.late() ? ", late" : "", taskId, task.courseId());
        return submissionRepository.findViewsByIdIn(result.ids());
    }

    private record Submitted(List<Long> ids, int version, boolean late) {
    }

    private SubmitRules rulesOf(Long taskId) {
        return taskRepository.findSubmitRulesById(taskId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.TASK_NOT_FOUND, "Task not found"));
    }

    /**
     * Whether a hand-in at {@code now} is accepted, and if so whether it is late. 409 ASSIGNMENT_CLOSED unless the
     * assignment is ACTIVE; 400 SUBMISSION_ALREADY_GRADED for graded work; after the deadline 400
     * SUBMISSION_DEADLINE_PASSED unless the assignment allows late work.
     */
    static boolean checkAccepting(SubmitRules rules, LocalDateTime now) {
        if (rules.assignmentStatus() != AssignmentStatus.ACTIVE) {
            throw ApiException.conflict(ErrorCodes.ASSIGNMENT_CLOSED, "Задание закрыто, новые решения не принимаются");
        }
        if (rules.status() == TaskStatus.GRADED) {
            throw alreadyGraded();
        }
        boolean late = isLate(rules.deadline(), now);
        if (late && !rules.allowLate()) {
            throw ApiException.badRequest(ErrorCodes.SUBMISSION_DEADLINE_PASSED, "Срок сдачи истёк "
                    + DEADLINE_FORMAT.format(rules.deadline()) + ", задание не принимает работы после срока");
        }
        return late;
    }

    /** Late means strictly after the deadline: at the deadline itself the work is still on time. */
    static boolean isLate(LocalDateTime deadline, LocalDateTime now) {
        return deadline != null && now.isAfter(deadline);
    }

    private static ApiException alreadyGraded() {
        return ApiException.badRequest(ErrorCodes.SUBMISSION_ALREADY_GRADED,
                "Работа уже оценена, сдать её заново нельзя");
    }

    /** Submissions of one task, newest first: for its student, the course's teacher or ADMIN. */
    @Transactional(readOnly = true)
    public List<SubmissionResponse> listForTask(Long taskId, String email) {
        User user = access.currentUser(email);
        TaskAccess task = taskRepository.findAccessById(taskId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.TASK_NOT_FOUND, "Task not found"));
        authorizeRead(user, task.userId(), task.courseId(), "task " + taskId);
        return submissionRepository.findViewsByTaskId(taskId);
    }

    /** Every student's submissions for an assignment; for the course's teacher or ADMIN. */
    @Transactional(readOnly = true)
    public List<SubmissionResponse> listForAssignment(Long assignmentId, String email) {
        User user = access.currentUser(email);
        Long courseId = assignmentRepository.findCourseIdById(assignmentId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
        access.authorizeCourse(user, courseId, Permission.MANAGE_ASSIGNMENTS);
        return submissionRepository.findViewsByAssignmentId(assignmentId);
    }

    @Transactional(readOnly = true)
    public SubmissionContent download(Long id, String email) {
        User user = access.currentUser(email);
        SubmissionAccess submission = submissionRepository.findAccessById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.SUBMISSION_NOT_FOUND, "Submission not found"));
        authorizeRead(user, submission.studentId(), submission.courseId(), "submission " + id);
        return new SubmissionContent(submission, storageService.load(submission.storedFileName()));
    }

    /** The submitting student reads their own work; otherwise the course must be managed by the user (403/404). */
    private void authorizeRead(User user, Long studentId, Long courseId, String what) {
        if (Objects.equals(studentId, user.getId())) {
            return;
        }
        if (courseId == null) {
            auditService.record(user.getEmail(), AuditService.ACCESS_DENIED, "VIEW " + what);
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED, "Access denied");
        }
        access.authorizeCourse(user, courseId, Permission.MANAGE_ASSIGNMENTS);
    }

    private record PreparedFile(String fileName, String contentType, byte[] content) {
    }

    private static List<PreparedFile> prepare(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.UPLOAD_EMPTY, "Не выбрано ни одного файла");
        }
        if (files.size() > MAX_FILES_PER_SUBMISSION) {
            throw ApiException.badRequest(ErrorCodes.UPLOAD_TOO_MANY_FILES, "К решению можно приложить не больше " + MAX_FILES_PER_SUBMISSION + " файлов");
        }
        List<PreparedFile> prepared = new ArrayList<>();
        for (MultipartFile file : files) {
            String fileName = DocumentService.sanitizeFileName(file.getOriginalFilename());
            if (file.isEmpty()) {
                throw ApiException.badRequest(ErrorCodes.UPLOAD_EMPTY, "Файл «" + fileName + "» пустой");
            }
            if (!DocumentService.isAllowedDocument(fileName)) {
                throw ApiException.badRequest(ErrorCodes.UPLOAD_UNSUPPORTED_TYPE, "Файл «" + fileName + "»: неподдерживаемый формат");
            }
            if (file.getSize() > DocumentService.MAX_DOCUMENT_BYTES) {
                throw ApiException.badRequest(ErrorCodes.UPLOAD_TOO_LARGE, "Файл «" + fileName + "» больше "
                        + DocumentService.MAX_DOCUMENT_BYTES / (1024 * 1024) + " МБ");
            }
            try {
                // The type comes from the extension, never from the client, so a download can't be served as HTML.
                prepared.add(new PreparedFile(fileName, DocumentService.contentTypeOf(fileName), file.getBytes()));
            } catch (IOException ex) {
                throw new UncheckedIOException("Could not read upload '" + fileName + "'", ex);
            }
        }
        return prepared;
    }
}
