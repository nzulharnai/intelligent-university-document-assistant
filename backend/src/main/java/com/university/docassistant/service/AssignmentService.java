package com.university.docassistant.service;

import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.AssignmentDtos.CreateAssignmentRequest;
import com.university.docassistant.dto.AssignmentDtos.PublishResponse;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 * What the AI finds is a DRAFT until the course teacher (or ADMIN) publishes it: students neither see drafts nor get
 * tasks for them. app.ai.auto-publish-assignments=true restores the old behavior (AI assignments ACTIVE at once).
 * Assignments created by hand are ACTIVE immediately.
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
    /** False (default): AI assignments start as DRAFT; true: ACTIVE at once, as before drafts existed. */
    private final boolean autoPublish;

    /** For tests: masking with the default settings. */
    public AssignmentService(AssignmentRepository assignmentRepository, TaskRepository taskRepository,
                             CourseRepository courseRepository, DocumentRepository documentRepository,
                             AccessControlService access, AuditService auditService, AssignmentEvents events,
                             boolean autoPublish) {
        this(assignmentRepository, taskRepository, courseRepository, documentRepository, access, auditService, events,
                new DocumentPrivacyService(new PrivacyProperties(null, null)), autoPublish);
    }

    @Autowired
    public AssignmentService(AssignmentRepository assignmentRepository, TaskRepository taskRepository,
                             CourseRepository courseRepository, DocumentRepository documentRepository,
                             AccessControlService access, AuditService auditService, AssignmentEvents events,
                             DocumentPrivacyService privacyService,
                             @Value("${app.ai.auto-publish-assignments:false}") boolean autoPublish) {
        this.privacyService = privacyService;
        this.assignmentRepository = assignmentRepository;
        this.taskRepository = taskRepository;
        this.courseRepository = courseRepository;
        this.documentRepository = documentRepository;
        this.access = access;
        this.auditService = auditService;
        this.events = events;
        this.autoPublish = autoPublish;
    }

    static final String NOT_DRAFTS_OF_COURSE = "Опубликовать можно только черновики этого курса; список устарел — "
            + "обновите страницу";

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

    /** The course's AI drafts waiting for review; for its teacher or ADMIN. */
    @Transactional(readOnly = true)
    public PageResponse<AssignmentResponse> drafts(Long courseId, String email, Pageable pageable) {
        access.authorizeCourse(access.currentUser(email), courseId, Permission.MANAGE_ASSIGNMENTS);
        return PageResponse.of(assignmentRepository.findDraftViewsByCourseId(courseId, pageable));
    }

    /** DRAFT -> ACTIVE, then every enrolled student gets the task; for the course teacher or ADMIN. */
    @Transactional
    public AssignmentResponse publish(Long id, String email) {
        User user = access.currentUser(email);
        Assignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
        Long courseId = assignment.getCourse().getId();
        access.authorizeCourse(user, courseId, Permission.MANAGE_ASSIGNMENTS);
        requirePublishable(assignment);
        publishAndHandOut(user, courseId, List.of(assignment));
        return assignmentRepository.findViewById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
    }

    /**
     * Publishes several drafts of the course at once, all or nothing: every id must be a DRAFT of this course with a
     * title, otherwise 400 and nothing changes. For the course teacher or ADMIN.
     */
    @Transactional
    public PublishResponse publishAll(Long courseId, List<Long> ids, String email) {
        User user = access.currentUser(email);
        access.authorizeCourse(user, courseId, Permission.MANAGE_ASSIGNMENTS);
        List<Long> distinct = ids.stream().distinct().toList();
        List<Assignment> drafts = assignmentRepository.findAllById(distinct);
        // An unknown id and one of another course get the same answer, so ids of other courses reveal nothing.
        if (drafts.size() != distinct.size()
                || drafts.stream().anyMatch(assignment -> !courseId.equals(assignment.getCourse().getId()))) {
            throw ApiException.badRequest(ErrorCodes.ASSIGNMENT_NOT_DRAFT, NOT_DRAFTS_OF_COURSE);
        }
        drafts.forEach(AssignmentService::requirePublishable);
        int tasksCreated = publishAndHandOut(user, courseId, drafts);
        return new PublishResponse(drafts.size(), tasksCreated);
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
        // A DRAFT was never handed out, so it has no student work: it can always go.
        boolean draft = assignmentRepository.findStatusById(id).orElse(null) == AssignmentStatus.DRAFT;
        if (!draft && !taskRepository.findAssignmentIdsWithActivity(List.of(id)).isEmpty()) {
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
     * for DRAFT, ACTIVE and CLOSED; the students' tasks of a published one get the new values, their statuses and
     * submissions stay as they are. A deadline change of an ACTIVE assignment is audited and reported to
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
        if (assignment.getStatus() != AssignmentStatus.DRAFT) {
            taskRepository.syncWithAssignments(courseId);
        }
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
     * an ACTIVE assignment; a DRAFT is published instead. For the course teacher or ADMIN.
     */
    @Transactional
    public AssignmentResponse reopen(Long id, String email) {
        User user = access.currentUser(email);
        Assignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
        Long courseId = assignment.getCourse().getId();
        access.authorizeCourse(user, courseId, Permission.MANAGE_ASSIGNMENTS);
        if (assignment.getStatus() == AssignmentStatus.DRAFT) {
            throw ApiException.badRequest(ErrorCodes.ASSIGNMENT_IS_DRAFT,
                    "Черновик нельзя открыть снова: опубликуйте его");
        }
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

    /** "Это другое задание": the teacher says the draft is not a duplicate after all. For the teacher or ADMIN. */
    @Transactional
    public AssignmentResponse notDuplicate(Long id, String email) {
        User user = access.currentUser(email);
        Assignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment not found"));
        access.authorizeCourse(user, assignment.getCourse().getId(), Permission.MANAGE_ASSIGNMENTS);
        if (assignment.getDuplicateOf() != null) {
            assignment.setDuplicateOf(null);
            assignmentRepository.saveAndFlush(assignment);
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
        if (assignment.getStatus() == AssignmentStatus.DRAFT) {
            // A closed assignment is shown to students; a draft must be reviewed first.
            throw ApiException.badRequest(ErrorCodes.ASSIGNMENT_IS_DRAFT,
                    "Черновик нельзя закрыть: опубликуйте его или удалите");
        }
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
     * Makes the document's assignments match what the AI found: items already there (same title and deadline) are
     * kept as they are (a draft stays a draft, a published one keeps its students' progress), new ones are created as
     * DRAFT (ACTIVE with auto-publish). Vanished drafts are simply removed - no student ever saw them. Vanished
     * published ones are removed, unless their students have already worked on them: those are closed (a re-analysis
     * may just word a title differently). Then every enrolled student gets the tasks of the ACTIVE ones. Runs in the
     * caller's transaction; returns the number of assignments found.
     * <p>
     * Assignments the teacher took over (edited, closed or reopened) are matched by the key they had before and are
     * never changed or removed here: the teacher's word wins over the AI's.
     */
    @Transactional
    public int replaceFromDocument(Long documentId, Long courseId, List<TaskExtractDto> found) {
        return replaceFromDocument(documentId, courseId, found, true);
    }

    /**
     * Like {@link #replaceFromDocument(Long, Long, List)}. {@code complete} false: {@code found} comes from only part
     * of the document (an AI chunk failed), so the assignments it lacks are kept instead of removed or closed.
     */
    @Transactional
    public int replaceFromDocument(Long documentId, Long courseId, List<TaskExtractDto> found, boolean complete) {
        Map<String, Assignment> existing = new HashMap<>();
        List<Long> staleDrafts = new ArrayList<>();
        for (Assignment assignment : assignmentRepository.findBySourceDocumentId(documentId)) {
            if (existing.putIfAbsent(matchKey(assignment), assignment) != null
                    && assignment.getStatus() == AssignmentStatus.DRAFT && assignment.getAiMatchKey() == null) {
                staleDrafts.add(assignment.getId()); // A second draft with the same title and deadline.
            }
        }
        List<Assignment> kept = new ArrayList<>();
        for (TaskExtractDto item : found) {
            Assignment assignment = existing.remove(key(item.title(), item));
            if (assignment == null) {
                assignment = Assignment.builder()
                        .title(item.title())
                        .deadline(item.deadline())
                        .status(autoPublish ? AssignmentStatus.ACTIVE : AssignmentStatus.DRAFT)
                        .course(courseRepository.getReferenceById(courseId))
                        .sourceDocument(documentRepository.getReferenceById(documentId))
                        .build();
            }
            if (assignment.getAiMatchKey() == null) {
                assignment.setRequirements(item.description());
            }
            kept.add(assignment);
        }
        List<Assignment> vanished = new ArrayList<>();
        for (Assignment assignment : existing.values()) {
            if (assignment.getAiMatchKey() != null || !complete) {
                continue; // Taken over by the teacher, or maybe in a part the AI did not answer for: stays.
            }
            if (assignment.getStatus() == AssignmentStatus.DRAFT) {
                staleDrafts.add(assignment.getId());
            } else {
                vanished.add(assignment);
            }
        }
        if (!staleDrafts.isEmpty()) {
            taskRepository.deleteByAssignmentIdIn(staleDrafts); // None expected: drafts are never handed out.
            assignmentRepository.deleteAllByIdInBatch(staleDrafts);
        }
        // No signed-in user: the analysis runs in the background.
        retire(vanished, null, "no longer found by the AI in document " + documentId);
        List<Assignment> created = kept.stream().filter(assignment -> assignment.getId() == null).toList();
        assignmentRepository.saveAll(kept);
        assignmentRepository.flush();
        markDuplicates(courseId, created);
        handOut(courseId);
        if (autoPublish && !created.isEmpty()) {
            // Published at once, without a teacher's review: the students hear about them like any publication.
            events.published(courseId, courseRepository.findById(courseId).map(Course::getTitle).orElse(""),
                    created.stream().map(Assignment::getTitle).toList());
        }
        return kept.size();
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

    private static void requirePublishable(Assignment assignment) {
        if (assignment.getStatus() != AssignmentStatus.DRAFT) {
            throw ApiException.badRequest(ErrorCodes.ASSIGNMENT_NOT_DRAFT, "Задание «" + assignment.getTitle()
                    + "» уже опубликовано");
        }
        if (assignment.getTitle() == null || assignment.getTitle().isBlank()) {
            throw ApiException.badRequest(ErrorCodes.ASSIGNMENT_TITLE_REQUIRED,
                    "У черновика нет названия: без него задание нельзя опубликовать");
        }
    }

    /** Makes the drafts ACTIVE, hands them out and audits the count; returns the number of student tasks created. */
    private int publishAndHandOut(User user, Long courseId, List<Assignment> drafts) {
        drafts.forEach(assignment -> assignment.setStatus(AssignmentStatus.ACTIVE));
        assignmentRepository.saveAllAndFlush(drafts);
        int tasksCreated = handOut(courseId);
        events.published(courseId, drafts.get(0).getCourse().getTitle(), drafts.stream().map(Assignment::getTitle).toList());
        auditService.record(user.getEmail(), AuditService.ASSIGNMENT_PUBLISHED, drafts.size()
                + " assignment(s) of course " + courseId + ": " + drafts.stream().map(Assignment::getId).toList()
                + ", " + tasksCreated + " student task(s)");
        log.info("User {} published {} assignment(s) of course {} ({} student task(s))",
                user.getEmail(), drafts.size(), courseId, tasksCreated);
        return tasksCreated;
    }

    private void auditClose(String actorEmail, Assignment assignment, String reason) {
        Long courseId = assignment.getCourse() == null ? null : assignment.getCourse().getId();
        auditService.record(actorEmail, AuditService.ASSIGNMENT_CLOSED, "Assignment " + assignment.getId()
                + " \"" + assignment.getTitle() + "\" of course " + courseId + ": " + reason);
        log.info("Assignment {} of course {} closed ({}) by {}", assignment.getId(), courseId, reason,
                actorEmail == null ? "the system" : actorEmail);
    }

    /**
     * Flags each new AI draft that looks like an assignment already in the course (any source, any status;
     * AssignmentTitles.likelySame), preferring a published one, then the oldest. Drafts of this same analysis are not
     * compared with each other, so two drafts never point at one another. Only flags: nothing is merged or removed.
     */
    private void markDuplicates(Long courseId, List<Assignment> created) {
        List<Assignment> drafts = created.stream()
                .filter(assignment -> assignment.getStatus() == AssignmentStatus.DRAFT).toList();
        if (drafts.isEmpty()) {
            return;
        }
        Set<Long> batch = new HashSet<>(drafts.stream().map(Assignment::getId).toList());
        List<AssignmentRepository.MatchInfo> candidates = assignmentRepository.findMatchInfoByCourseId(courseId).stream()
                .filter(candidate -> !batch.contains(candidate.id()))
                .sorted(Comparator.comparing((AssignmentRepository.MatchInfo candidate) ->
                                candidate.status() == AssignmentStatus.DRAFT)
                        .thenComparing(AssignmentRepository.MatchInfo::id))
                .toList();
        List<Assignment> marked = new ArrayList<>();
        for (Assignment draft : drafts) {
            candidates.stream()
                    .filter(candidate -> AssignmentTitles.likelySame(draft.getTitle(), draft.getDeadline(),
                            candidate.title(), candidate.deadline()))
                    .findFirst()
                    .ifPresent(original -> {
                        draft.setDuplicateOf(assignmentRepository.getReferenceById(original.id()));
                        marked.add(draft);
                    });
        }
        if (!marked.isEmpty()) {
            assignmentRepository.saveAllAndFlush(marked);
            log.info("Course {}: {} new AI draft(s) look like existing assignments", courseId, marked.size());
        }
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
