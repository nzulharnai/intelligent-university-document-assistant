package com.university.docassistant.service;

import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.dto.TaskDtos.TaskStateFilter;
import com.university.docassistant.dto.TaskDtos.TaskSummary;
import com.university.docassistant.dto.TaskResponse;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.repository.TaskRepository.TaskAccess;
import com.university.docassistant.security.AccessControlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A student's own tasks (copies of their courses' assignments): listing them, the upcoming deadlines, and moving
 * them between statuses. Only the task's user can change it. On an assignment task the student moves only between
 * PENDING and IN_PROGRESS; SUBMITTED and GRADED are the system's (a submission, the teacher's grade). Legacy tasks
 * without an assignment keep their free PENDING / IN_PROGRESS / COMPLETED changes.
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final AccessControlService access;
    private final DocumentPrivacyService privacyService;
    private final Clock clock;

    @Autowired
    public TaskService(TaskRepository taskRepository, AccessControlService access,
                       DocumentPrivacyService privacyService, Clock clock) {
        this.taskRepository = taskRepository;
        this.access = access;
        this.privacyService = privacyService;
        this.clock = clock;
    }

    /** For tests: masking with the default settings. */
    TaskService(TaskRepository taskRepository, AccessControlService access, Clock clock) {
        this(taskRepository, access, new DocumentPrivacyService(new PrivacyProperties(null, null)), clock);
    }

    /** Tasks belong to students: their documents' names always come with personal data masked (US-09). */
    private TaskResponse shown(TaskResponse task) {
        return task.withDocumentName(privacyService.maskFileName(task.documentName()));
    }

    /**
     * The current user's own tasks, nothing else: by state (overdue against the shared clock) and optionally one
     * course. Score and feedback come with them, since they are the user's own results.
     */
    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> listMine(String email, TaskStateFilter state, Long courseId, Pageable pageable) {
        User user = access.currentUser(email);
        return PageResponse.of(taskRepository.findMyViews(user.getId(), (state == null ? TaskStateFilter.ALL : state).name(),
                courseId, LocalDateTime.now(clock), pageable).map(this::shown));
    }

    /** "К сдаче: N · Просрочено: M · Оценено: K" for the current user. */
    @Transactional(readOnly = true)
    public TaskSummary summary(String email) {
        User user = access.currentUser(email);
        return taskRepository.summarizeMine(user.getId(), LocalDateTime.now(clock));
    }

    /** Unfinished tasks with a deadline from now on (app time zone), nearest first. */
    @Transactional(readOnly = true)
    public List<TaskResponse> upcoming(String email, int limit) {
        User user = access.currentUser(email);
        return taskRepository.findUpcomingViews(user.getId(), LocalDateTime.now(clock), PageRequest.of(0, limit))
                .getContent().stream().map(this::shown).toList();
    }

    /**
     * The student's own status change. 400 TASK_STATUS_SYSTEM_ONLY for SUBMITTED or GRADED by hand, for COMPLETED on
     * an assignment task (the work is handed in as a submission instead), and for leaving SUBMITTED or GRADED.
     */
    @Transactional
    public TaskResponse updateStatus(Long id, TaskStatus status, String email) {
        User user = access.currentUser(email);
        TaskAccess task = taskRepository.findAccessById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.TASK_NOT_FOUND, "Task not found"));
        access.authorizeOwnTask(user, task);
        boolean assignmentTask = task.assignmentId() != null;
        if (status.isSystemOnly() || (assignmentTask && status == TaskStatus.COMPLETED)) {
            throw systemOnly();
        }
        if (assignmentTask) {
            if (taskRepository.updateStudentStatus(id, status) == 0) {
                throw systemOnly();     // Already SUBMITTED or GRADED.
            }
        } else {
            taskRepository.updateStatus(id, status);
        }
        return taskRepository.findViewById(id).map(this::shown)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.TASK_NOT_FOUND, "Task not found"));
    }

    private static ApiException systemOnly() {
        return ApiException.badRequest(ErrorCodes.TASK_STATUS_SYSTEM_ONLY,
                "Статусы «Сдано» и «Оценено» ставятся автоматически: сдайте решение, оценку выставит преподаватель");
    }
}
