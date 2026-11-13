package com.university.docassistant.service;

import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.AssignmentDtos.CreateAssignmentRequest;
import com.university.docassistant.dto.AssignmentDtos.UpdateAssignmentRequest;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.dto.TaskExtractDto;
import com.university.docassistant.entity.Assignment;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.CourseRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import com.university.docassistant.util.AssignmentTitles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Document -> Assignment -> student Task. Assignments belong to a course: the AI finds them in its documents, its
 * teacher can add more. Every student enrolled in the course gets a task per ACTIVE assignment, also when enrolling
 * later (CourseService calls {@link #handOut}).
 * <p>
 * What the AI finds is ACTIVE at once, handed out and announced to the students like an assignment created by hand
 * (changed at the course teacher's request: no review step). An AI item the course already has (same normalized title,
 * deadline on the same day) is skipped. The teacher fixes AI mistakes afterwards: edit, close or delete.
 * <p>
 * Student work is never deleted: an assignment with any task that has a submission or a status other than PENDING
 * ("activity") is CLOSED instead (kept with its tasks and submissions, no longer handed out or accepting files). Only
 * assignments without activity are removed. Every close is audited.
 */
@Slf4j
@Service
public class AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final TaskRepository taskRepository;
    private final CourseRepository courseRepository;
    private final DocumentRepository documentRepository;
    private final AccessControlService access;
    private final AuditService auditService;
    private final AssignmentEvents events;
    private final DocumentPrivacyService privacyService;

    /** For tests: masking with the default settings. */
    public AssignmentService(AssignmentRepository assignmentRepository, TaskRepository taskRepository,
                             CourseRepository courseRepository, DocumentRepository documentRepository,
                             AccessControlService access, AuditService auditService, AssignmentEvents events) {
        this(assignmentRepository, taskRepository, courseRepository, documentRepository, access, auditService, events,
                new DocumentPrivacyService(new PrivacyProperties(null, null)));
    }

    @Autowired
    public AssignmentService(AssignmentRepository assignmentRepository, TaskRepository taskRepository,
                             CourseRepository courseRepository, DocumentRepository documentRepository,
                             AccessControlService access, AuditService auditService, AssignmentEvents events,
                             DocumentPrivacyService privacyService) {
        this.privacyService = privacyService;
        this.assignmentRepository = assignmentRepository;
        this.taskRepository = taskRepository;
        this.courseRepository = courseRepository;
        this.documentRepository = documentRepository;
        this.access = access;
        this.auditService = auditService;
        this.events = events;
    }

    static final String HAS_ACTIVITY = "Студенты уже работают над этим заданием (есть решения или изменённые статусы), "
            + "поэтому удалить его нельзя. Закройте задание: оно останется в курсе вместе с решениями студентов, "
            + "но новые решения приниматься не будут.";

    @Transactional(readOnly = true)
    public PageResponse<AssignmentResponse> list(Long courseId, String email, Pageable pageable) {
        User user = access.currentUser(email);
        Course course = access.authorizeCourse(user, courseId, Permission.VIEW_COURSE);
        Page<AssignmentResponse> page = assignmentRepository.findViewsByCourseId(courseId, pageable);
        if (access.can(user, course, Permission.MANAGE_COURSE)) {
            return PageResponse.of(page);
        }
        // US-09: students see the source documents' names with personal data masked.
        return PageResponse.of(page.map(row ->
                row.withSourceDocumentName(privacyService.maskFileName(row.sourceDocumentName()))));
    }

    @Transactional
    public AssignmentResponse create(Long courseId, CreateAssignmentRequest request, String email) {
        User user = access.currentUser(email);
        Course course = access.authorizeCourse(user, courseId, Permission.MANAGE_ASSIGNMENTS);
        Assignment assignment = assignmentRepository.save(Assignment.builder()
                .title(request.title().strip())
                .requirements(request.description() == null || request.description().isBlank()
                        ? null : request.description().strip())
                .deadline(request.deadline())
                .course(course)
                .build());
        int handedOut = handOut(courseId);
        events.published(courseId, course.getTitle(), List.of(assignment.getTitle()));
        auditService.record(user.getEmail(), AuditService.ASSIGNMENT_CREATED,
                "Assignment " + assignment.getId() + " \"" + assignment.getTitle() + "\" in course " + courseId);
        log.info("User {} created assignment {} in course {} ({} student task(s))",
                user.getEmail(), assignment.getId(), courseId, handedOut);
        return assignmentRepository.findViewById(assignment.getId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
    }

    /**
     * Removes the assignment and its students' tasks; for the course teacher or ADMIN. An assignment with student work
     * is refused with 409: it can only be closed ({@link #close}).
     */
    @Transactional
    public void delete(Long id, String email) {
        User user = access.currentUser(email);
        Long courseId = assignmentRepository.findCourseIdById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
        access.authorizeCourse(user, courseId, Permission.MANAGE_ASSIGNMENTS);
        if (!taskRepository.findAssignmentIdsWithActivity(List.of(id)).isEmpty()) {
            throw ApiException.conflict(ErrorCodes.ASSIGNMENT_HAS_ACTIVITY, HAS_ACTIVITY);
        }
        taskRepository.deleteByAssignmentIdIn(List.of(id));
        assignmentRepository.deleteById(id);
        auditService.record(user.getEmail(), AuditService.ASSIGNMENT_DELETED,
                "Assignment " + id + " of course " + courseId);
        log.info("User {} deleted assignment {} of course {}", user.getEmail(), id, courseId);
    }

    /**
     * The course teacher (or ADMIN) fixes an assignment - typically an AI mistake: title, requirements, deadline. Works
     * for ACTIVE and CLOSED; the students' tasks get the new values, their statuses and submissions stay as they
     * are. A deadline change of an ACTIVE assignment is audited and reported to
     * {@link AssignmentEvents#deadlineChanged}.
     */
    @Transactional
    public AssignmentResponse update(Long id, UpdateAssignmentRequest request, String email) {
        User user = access.currentUser(email);
        Assignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
        Long courseId = assignment.getCourse().getId();
        access.authorizeCourse(user, courseId, Permission.MANAGE_ASSIGNMENTS);
        if (request.title() == null || request.title().isBlank()) {
            throw ApiException.badRequest(ErrorCodes.ASSIGNMENT_TITLE_REQUIRED, "Укажите название задания");
        }
        requireMaxScoreAboveGrades(id, request.maxScore());
        takeOver(assignment);
        LocalDateTime previousDeadline = assignment.getDeadline();
        assignment.setTitle(request.title().strip());
        assignment.setRequirements(request.description() == null || request.description().isBlank()
                ? null : request.description().strip());
        assignment.setDeadline(request.deadline());
        if (request.allowLate() != null) {
            assignment.setAllowLate(request.allowLate());
        }
        assignment.setMaxScore(request.maxScore());
        assignmentRepository.saveAndFlush(assignment);
        taskRepository.syncWithAssignments(courseId);
        if (assignment.getStatus() == AssignmentStatus.ACTIVE && !Objects.equals(previousDeadline, request.deadline())) {
            auditService.record(user.getEmail(), AuditService.ASSIGNMENT_DEADLINE_CHANGED, "Assignment " + id + " \""
                    + assignment.getTitle() + "\" of course " + courseId + ": " + deadlineText(previousDeadline)
                    + " -> " + deadlineText(request.deadline()));
            events.deadlineChanged(assignment, previousDeadline, request.deadline());
        }
        log.info("User {} edited assignment {} of course {}", user.getEmail(), id, courseId);
        return assignmentRepository.findViewById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
    }

    /**
     * CLOSED -> ACTIVE: submissions are accepted again and students enrolled meanwhile get their task. Idempotent for
     * an ACTIVE assignment. For the course teacher or ADMIN.
     */
    @Transactional
    public AssignmentResponse reopen(Long id, String email) {
        User user = access.currentUser(email);
        Assignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
        Long courseId = assignment.getCourse().getId();
        access.authorizeCourse(user, courseId, Permission.MANAGE_ASSIGNMENTS);
        if (assignment.getStatus() == AssignmentStatus.CLOSED) {
            takeOver(assignment);
            assignment.setStatus(AssignmentStatus.ACTIVE);
            assignmentRepository.saveAndFlush(assignment);
            int handedOut = handOut(courseId);
            auditService.record(user.getEmail(), AuditService.ASSIGNMENT_REOPENED, "Assignment " + id + " \""
                    + assignment.getTitle() + "\" of course " + courseId + ", " + handedOut + " new student task(s)");
            log.info("User {} reopened assignment {} of course {} ({} new task(s))", user.getEmail(), id, courseId,
                    handedOut);
        }
        return assignmentRepository.findViewById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
    }

    /**
     * Closes the assignment: it stays in the course with its tasks and submissions, is not handed out to students who
     * enroll later and takes no more submissions. Idempotent; for the course teacher or ADMIN.
     */
    @Transactional
    public AssignmentResponse close(Long id, String email) {
        User user = access.currentUser(email);
        Assignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
        Long courseId = assignment.getCourse().getId();
        access.authorizeCourse(user, courseId, Permission.MANAGE_ASSIGNMENTS);
        if (assignment.getStatus() != AssignmentStatus.CLOSED) {
            takeOver(assignment);
            assignment.setStatus(AssignmentStatus.CLOSED);
            assignmentRepository.saveAndFlush(assignment);
            auditClose(user.getEmail(), assignment, "closed by hand");
        }
        return assignmentRepository.findViewById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
    }

    /**
     * The documents are about to be deleted: their assignments without student work are removed with their tasks; the
     * others are closed and unlinked from the document (they stay in the course and in the students' tasks, with
     * their submissions). Tasks with student work that point at the documents directly are unlinked too. Runs in the
     * caller's transaction, before it deletes the documents.
     */
    @Transactional
    public void releaseDocuments(Collection<Long> documentIds, String actorEmail) {
        if (documentIds.isEmpty()) {
            return;
        }
        List<Assignment> kept = retire(assignmentRepository.findBySourceDocumentIdIn(documentIds), actorEmail,
                "its document was deleted");
        if (!kept.isEmpty()) {
            kept.forEach(assignment -> assignment.setSourceDocument(null));
            assignmentRepository.saveAllAndFlush(kept);
            taskRepository.detachDocumentFromAssignmentTasks(kept.stream().map(Assignment::getId).toList());
        }
        taskRepository.detachDocumentFromTasksWithActivity(documentIds);
    }

    /**
     * Makes the document's assignments match what the AI found; see {@link #replaceFromDocument(Long, Long, List,
     * boolean)}. Runs in the caller's transaction; returns the number of assignments found.
     */
    @Transactional
    public int replaceFromDocument(Long documentId, Long courseId, List<TaskExtractDto> found) {
        return replaceFromDocument(documentId, courseId, found, true);
    }

    /**
     * Makes the document's assignments match what the AI found:
     * <ul>
     *   <li>items already there (same title and deadline) are kept as they are, with their students' progress; an
     *       item that is the same assignment as one of this document's own, only worded differently
     *       ({@link AssignmentTitles#sameAssignment}), keeps that one too;</li>
     *   <li>an item the course already has from elsewhere (another document, by hand, or found twice in this one) is
     *       skipped and logged;</li>
     *   <li>the other items become new ACTIVE assignments: every enrolled student gets the task and is told
     *       (ASSIGNMENT_PUBLISHED, one notification for the batch), and the publication is audited;</li>
     *   <li>vanished ones are removed, unless their students have already worked on them: those are closed (a
     *       re-analysis may just word a title differently). {@code complete} false: {@code found} comes from only
     *       part of the document (an AI chunk failed), so nothing is removed or closed.</li>
     * </ul>
     * Assignments the teacher took over (edited, closed or reopened) are matched by the key they had before and are
     * never changed or removed here: the teacher's word wins over the AI's. Runs in the caller's transaction; returns
     * the number of the document's assignments from this analysis.
     */
    @Transactional
    public int replaceFromDocument(Long documentId, Long courseId, List<TaskExtractDto> found, boolean complete) {
        List<Assignment> own = assignmentRepository.findBySourceDocumentId(documentId);
        Set<Long> ownIds = new HashSet<>(own.stream().map(Assignment::getId).toList());
        Map<String, Assignment> existing = new LinkedHashMap<>();
        own.forEach(assignment -> existing.putIfAbsent(matchKey(assignment), assignment));
        // What a new item must not duplicate: the course's assignments from elsewhere.
        List<AssignmentRepository.MatchInfo> elsewhere = assignmentRepository.findMatchInfoByCourseId(courseId).stream()
                .filter(candidate -> !ownIds.contains(candidate.id()))
                .toList();
        List<Assignment> kept = new ArrayList<>();
        List<Assignment> created = new ArrayList<>();
        for (TaskExtractDto item : found) {
            Assignment assignment = existing.remove(key(item.title(), item));
            if (assignment == null) {
                assignment = takeSameAssignment(existing, item);
            }
            if (assignment == null) {
                Optional<String> original = alreadyInCourse(item, elsewhere, kept);
                if (original.isPresent()) {
                    log.info("Course {}: AI assignment \"{}\" ({}) from document {} skipped, the course already has "
                            + "\"{}\"", courseId, item.title(), deadlineText(item.deadline()), documentId,
                            original.get());
                    continue;
                }
                assignment = Assignment.builder()
                        .title(item.title())
                        .deadline(item.deadline())
                        .status(AssignmentStatus.ACTIVE)
                        .course(courseRepository.getReferenceById(courseId))
                        .sourceDocument(documentRepository.getReferenceById(documentId))
                        .build();
                created.add(assignment);
            }
            if (assignment.getAiMatchKey() == null) {
                assignment.setRequirements(item.description());
            }
            kept.add(assignment);
        }
        List<Assignment> vanished = existing.values().stream()
                // Taken over by the teacher, or maybe in a part the AI did not answer for: stays.
                .filter(assignment -> assignment.getAiMatchKey() == null && complete)
                .toList();
        // No signed-in user: the analysis runs in the background.
        retire(vanished, null, "no longer found by the AI in document " + documentId);
        assignmentRepository.saveAll(kept);
        assignmentRepository.flush();
        int handedOut = handOut(courseId);
        if (!created.isEmpty()) {
            List<String> titles = created.stream().map(Assignment::getTitle).toList();
            events.published(courseId, courseRepository.findById(courseId).map(Course::getTitle).orElse(""), titles);
            auditService.record(null, AuditService.ASSIGNMENT_PUBLISHED, created.size()
                    + " AI assignment(s) from document " + documentId + " in course " + courseId + ": "
                    + created.stream().map(Assignment::getId).toList() + ", " + handedOut + " student task(s)");
            log.info("Course {}: {} AI assignment(s) from document {} published ({} student task(s))", courseId,
                    created.size(), documentId, handedOut);
        }
        return kept.size();
    }

    /** One of this document's own assignments that {@code item} names in other words; removed from {@code existing}. */
    private static Assignment takeSameAssignment(Map<String, Assignment> existing, TaskExtractDto item) {
        for (Map.Entry<String, Assignment> entry : existing.entrySet()) {
            Assignment assignment = entry.getValue();
            if (AssignmentTitles.sameAssignment(item.title(), item.deadline(), assignment.getTitle(),
                    assignment.getDeadline())) {
                existing.remove(entry.getKey());
                return assignment;
            }
        }
        return null;
    }

    /** The title of the course's assignment {@code item} duplicates: from elsewhere, or kept earlier in this batch. */
    private static Optional<String> alreadyInCourse(TaskExtractDto item, List<AssignmentRepository.MatchInfo> elsewhere,
                                                    List<Assignment> kept) {
        for (AssignmentRepository.MatchInfo candidate : elsewhere) {
            if (AssignmentTitles.sameAssignment(item.title(), item.deadline(), candidate.title(), candidate.deadline())) {
                return Optional.of(candidate.title());
            }
        }
        for (Assignment assignment : kept) {
            if (AssignmentTitles.sameAssignment(item.title(), item.deadline(), assignment.getTitle(),
                    assignment.getDeadline())) {
                return Optional.of(assignment.getTitle());
            }
        }
        return Optional.empty();
    }

    /** Creates the missing student tasks of the course and refreshes the others; returns how many were created. */
    @Transactional
    public int handOut(Long courseId) {
        taskRepository.syncWithAssignments(courseId);
        return taskRepository.fanOut(courseId);
    }

    /**
     * Deletes the assignments without student work (and their tasks); closes the others, auditing each that was still
     * ACTIVE. Returns the kept (closed) ones.
     */
    private List<Assignment> retire(List<Assignment> assignments, String actorEmail, String reason) {
        if (assignments.isEmpty()) {
            return List.of();
        }
        Set<Long> withActivity = new HashSet<>(taskRepository.findAssignmentIdsWithActivity(
                assignments.stream().map(Assignment::getId).toList()));
        List<Assignment> kept = new ArrayList<>();
        List<Long> removed = new ArrayList<>();
        for (Assignment assignment : assignments) {
            if (!withActivity.contains(assignment.getId())) {
                removed.add(assignment.getId());
                continue;
            }
            kept.add(assignment);
            if (assignment.getStatus() != AssignmentStatus.CLOSED) {
                assignment.setStatus(AssignmentStatus.CLOSED);
                auditClose(actorEmail, assignment, reason);
            }
        }
        if (!kept.isEmpty()) {
            assignmentRepository.saveAllAndFlush(kept);
        }
        if (!removed.isEmpty()) {
            taskRepository.deleteByAssignmentIdIn(removed);
            assignmentRepository.deleteAllByIdInBatch(removed);
        }
        return kept;
    }

    /**
     * A new max_score must not go below grades already given: 400 MAX_SCORE_BELOW_GRADES naming how many are above it.
     * Without a max score (null) grades may go up to GradingService.DEFAULT_MAX_SCORE (1000).
     */
    private void requireMaxScoreAboveGrades(Long assignmentId, Integer maxScore) {
        BigDecimal max = maxScore == null ? GradingService.DEFAULT_MAX_SCORE : BigDecimal.valueOf(maxScore);
        long above = taskRepository.countScoresAbove(assignmentId, max);
        if (above > 0) {
            throw ApiException.badRequest(ErrorCodes.MAX_SCORE_BELOW_GRADES,
                    gradesText(above) + " выше нового максимума — сначала измените их");
        }
    }

    /** "1 оценка", "3 оценки", "5 оценок", "21 оценка". */
    static String gradesText(long count) {
        long lastTwo = count % 100;
        long last = count % 10;
        String word = lastTwo >= 11 && lastTwo <= 14 ? "оценок"
                : last == 1 ? "оценка" : last >= 2 && last <= 4 ? "оценки" : "оценок";
        return count + " " + word;
    }

    private void auditClose(String actorEmail, Assignment assignment, String reason) {
        Long courseId = assignment.getCourse() == null ? null : assignment.getCourse().getId();
        auditService.record(actorEmail, AuditService.ASSIGNMENT_CLOSED, "Assignment " + assignment.getId()
                + " \"" + assignment.getTitle() + "\" of course " + courseId + ": " + reason);
        log.info("Assignment {} of course {} closed ({}) by {}", assignment.getId(), courseId, reason,
                actorEmail == null ? "the system" : actorEmail);
    }

    /** The key re-analysis matches this assignment by: the one from before the teacher took it over, if they did. */
    private static String matchKey(Assignment assignment) {
        return assignment.getAiMatchKey() != null ? assignment.getAiMatchKey() : key(assignment.getTitle(), assignment);
    }

    /** Remembers the AI key of an assignment found in a document before the teacher changes it; once only. */
    private static void takeOver(Assignment assignment) {
        if (assignment.getSourceDocument() != null && assignment.getAiMatchKey() == null) {
            assignment.setAiMatchKey(key(assignment.getTitle(), assignment));
        }
    }

    private static String deadlineText(LocalDateTime deadline) {
        return deadline == null ? "no deadline" : deadline.toString();
    }

    private static String key(String title, Assignment assignment) {
        return title.strip().toLowerCase(Locale.ROOT) + "|" + Objects.toString(assignment.getDeadline(), "");
    }

    private static String key(String title, TaskExtractDto item) {
        return title.strip().toLowerCase(Locale.ROOT) + "|" + Objects.toString(item.deadline(), "");
    }
}
