package com.university.docassistant.service;

import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.GradingDtos.GradeRequest;
import com.university.docassistant.dto.GradingDtos.GradingFilter;
import com.university.docassistant.dto.GradingDtos.GradingResponse;
import com.university.docassistant.dto.GradingDtos.GradingStudent;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.dto.SubmissionDtos.SubmissionResponse;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskRepository.GradeTarget;
import com.university.docassistant.repository.TaskRepository.GradingRow;
import com.university.docassistant.repository.TaskSubmissionRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * US-11: the course's teacher (or ADMIN) grades students' work on an assignment and gives feedback. A task is graded
 * whether or not something was handed in (0 for "не сдано" is a grade too); grading again replaces the grade, and
 * taking it back returns the task to SUBMITTED (or PENDING without a hand-in). Every change is audited with the old
 * and the new score; the student is notified (NotificationEvents: saved with the grade, e-mailed after commit).
 * The task's row is locked meanwhile, so a
 * hand-in arriving at the same moment is either before the grade or refused as already graded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GradingService {

    /** The highest score of an assignment without its own max_score. */
    static final BigDecimal DEFAULT_MAX_SCORE = BigDecimal.valueOf(1_000);

    private final TaskRepository taskRepository;
    private final TaskSubmissionRepository submissionRepository;
    private final AssignmentRepository assignmentRepository;
    private final AccessControlService access;
    private final AuditService auditService;
    private final NotificationEvents notificationEvents;
    private final Clock clock;

    @Transactional
    public GradingStudent grade(Long taskId, GradeRequest request, String email) {
        User grader = access.currentUser(email);
        GradeTarget target = authorize(grader, taskId);
        taskRepository.lockById(taskId);
        target = target(taskId);                     // As it is now, under the lock.
        BigDecimal score = validScore(request.score(), target.maxScore());
        String feedback = request.feedback() == null || request.feedback().isBlank() ? null : request.feedback().strip();
        taskRepository.grade(taskId, score, feedback, LocalDateTime.now(clock), grader);
        auditService.record(grader.getEmail(), AuditService.TASK_GRADED, "Task " + taskId + " of "
                + target.studentEmail() + " (assignment " + target.assignmentId() + "): "
                + scoreText(target.status() == TaskStatus.GRADED ? target.score() : null) + " -> " + scoreText(score));
        log.info("User {} graded task {} of assignment {}: {}", grader.getEmail(), taskId, target.assignmentId(), score);
        notificationEvents.taskGraded(taskId);
        return view(taskId);
    }

    /** Takes the grade back; idempotent for a task that is not graded. */
    @Transactional
    public GradingStudent ungrade(Long taskId, String email) {
        User user = access.currentUser(email);
        authorize(user, taskId);
        taskRepository.lockById(taskId);
        GradeTarget target = target(taskId);
        if (target.status() == TaskStatus.GRADED) {
            TaskStatus back = target.submittedAt() != null ? TaskStatus.SUBMITTED : TaskStatus.PENDING;
            taskRepository.clearGrade(taskId, back);
            auditService.record(user.getEmail(), AuditService.TASK_UNGRADED, "Task " + taskId + " of "
                    + target.studentEmail() + " (assignment " + target.assignmentId() + "): "
                    + scoreText(target.score()) + " removed, back to " + back);
            log.info("User {} took back the grade of task {}", user.getEmail(), taskId);
        }
        return view(taskId);
    }

    /** The assignment's grading page: header, progress counts and one page of enrolled students with their files. */
    @Transactional(readOnly = true)
    public GradingResponse list(Long assignmentId, GradingFilter filter, Pageable pageable, String email) {
        User user = access.currentUser(email);
        Long courseId = assignmentRepository.findCourseIdById(assignmentId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
        access.authorizeCourse(user, courseId, Permission.GRADE_WORK);
        AssignmentResponse assignment = assignmentRepository.findViewById(assignmentId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
        Page<GradingRow> rows = taskRepository.findGradingRows(assignmentId,
                (filter == null ? GradingFilter.ALL : filter).name(), pageable);
        Map<Long, List<SubmissionResponse>> files = currentFiles(rows.getContent().stream().map(GradingRow::taskId).toList());
        return new GradingResponse(assignment, taskRepository.summarizeGrading(assignmentId),
                PageResponse.of(rows.map(row -> student(row, files.getOrDefault(row.taskId(), List.of())))));
    }

    /** 404 for an unknown task, 400 for one without an assignment, 403 unless the user grades in its course. */
    private GradeTarget authorize(User user, Long taskId) {
        GradeTarget target = target(taskId);
        if (target.assignmentId() == null || target.courseId() == null) {
            throw ApiException.badRequest(ErrorCodes.TASK_NOT_GRADABLE, "Оценить можно только задачу задания курса");
        }
        access.authorizeCourse(user, target.courseId(), Permission.GRADE_WORK);
        return target;
    }

    private GradeTarget target(Long taskId) {
        return taskRepository.findGradeTargetById(taskId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.TASK_NOT_FOUND, "Task not found"));
    }

    /** 0..max (0..1000 without a max score), at most one decimal; returned without trailing zeros. */
    static BigDecimal validScore(BigDecimal score, Integer maxScore) {
        BigDecimal max = maxScore == null ? DEFAULT_MAX_SCORE : BigDecimal.valueOf(maxScore);
        BigDecimal normalized = score == null ? null : score.stripTrailingZeros();
        if (normalized == null || normalized.signum() < 0 || normalized.compareTo(max) > 0 || normalized.scale() > 1) {
            throw ApiException.badRequest(ErrorCodes.GRADE_SCORE_INVALID, "Оценка должна быть от 0 до "
                    + max.toPlainString() + ", не больше одного знака после запятой");
        }
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    private GradingStudent view(Long taskId) {
        GradingRow row = taskRepository.findGradingRowById(taskId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.TASK_NOT_FOUND, "Task not found"));
        return student(row, currentFiles(List.of(taskId)).getOrDefault(taskId, List.of()));
    }

    private Map<Long, List<SubmissionResponse>> currentFiles(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return submissionRepository.findCurrentViewsByTaskIdIn(taskIds).stream()
                .collect(Collectors.groupingBy(SubmissionResponse::taskId));
    }

    private static GradingStudent student(GradingRow row, List<SubmissionResponse> files) {
        return new GradingStudent(row.taskId(), row.studentId(), row.studentFullName(), row.studentEmail(),
                row.status(), row.submittedAt(), row.late(), row.score(), row.feedback(), row.gradedAt(), files);
    }

    private static String scoreText(BigDecimal score) {
        return score == null ? "—" : score.stripTrailingZeros().toPlainString();
    }

}
