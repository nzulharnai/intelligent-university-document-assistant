package com.university.docassistant.repository;

import com.university.docassistant.dto.TaskResponse;
import com.university.docassistant.dto.GradingDtos.GradingSummary;
import com.university.docassistant.dto.TaskDtos.TaskSummary;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.Task;
import com.university.docassistant.entity.TaskStatus;
import com.university.docassistant.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * List methods return {@link TaskResponse} rows built in the query; see {@link TaskResponse#SELECT}.
 * A student's tasks are copies of the ACTIVE assignments of the courses they are enrolled in: {@link #fanOut} creates
 * the missing ones and {@link #syncWithAssignments} carries over edited titles and deadlines; statuses are kept.
 * Tasks are never deleted while they carry student work (a status other than PENDING or a submission, see
 * {@link #ACTIVITY}): a student who leaves a course keeps them (hidden from their lists until they re-enroll, see
 * {@link #ENROLLED}), and assignments with such tasks are closed instead of deleted (AssignmentService).
 */
@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    /** JPQL condition on Task t: the student did something with it (changed its status or handed in a file). */
    String ACTIVITY = """
            (t.status <> com.university.docassistant.entity.TaskStatus.PENDING
             OR EXISTS (SELECT s.id FROM TaskSubmission s WHERE s.task = t))
            """;

    /**
     * JPQL condition on Task t, for the student's own lists: no course, or its owner is still enrolled in it; and never
     * a task of a DRAFT assignment (fanOut does not create those; this keeps it so if one ever existed).
     */
    String ENROLLED = """
            (t.course IS NULL OR EXISTS (SELECT e.enrolledAt FROM Enrollment e
                                         WHERE e.course = t.course AND e.student = t.user))
            AND (t.assignment IS NULL OR t.assignment.status <> com.university.docassistant.entity.AssignmentStatus.DRAFT)
            """;

    @Query(value = TaskResponse.SELECT + "WHERE t.user.id = :userId AND " + ENROLLED,
            countQuery = "SELECT COUNT(t) FROM Task t WHERE t.user.id = :userId AND " + ENROLLED)
    Page<TaskResponse> findViewsByUserId(@Param("userId") Long userId, Pageable pageable);

    /**
     * JPQL condition on Task t (assignment joined as a): still to hand in, deadline not passed at :now, and the
     * assignment (if any) still takes work. "К сдаче" in the student's summary and its filter.
     */
    String TO_DO = """
            (t.status IN (com.university.docassistant.entity.TaskStatus.PENDING,
                          com.university.docassistant.entity.TaskStatus.IN_PROGRESS)
             AND (t.deadline IS NULL OR t.deadline >= :now)
             AND (a.id IS NULL OR a.status = com.university.docassistant.entity.AssignmentStatus.ACTIVE))
            """;

    /** JPQL condition on Task t: nothing handed in and the deadline is before :now (strictly). "Просрочено". */
    String OVERDUE = """
            (t.status IN (com.university.docassistant.entity.TaskStatus.PENDING,
                          com.university.docassistant.entity.TaskStatus.IN_PROGRESS)
             AND t.deadline < :now)
            """;

    /** JPQL condition on Task t for "Мои задачи": the state filter (TaskStateFilter name in :state). */
    String MY_STATE = """
            (:state = 'ALL'
             OR (:state = 'NOT_STARTED' AND t.status = com.university.docassistant.entity.TaskStatus.PENDING)
             OR (:state = 'IN_PROGRESS' AND t.status = com.university.docassistant.entity.TaskStatus.IN_PROGRESS)
             OR (:state = 'SUBMITTED' AND t.status = com.university.docassistant.entity.TaskStatus.SUBMITTED)
             OR (:state = 'LATE' AND t.late = true)
             OR (:state = 'GRADED' AND t.status = com.university.docassistant.entity.TaskStatus.GRADED)
             OR (:state = 'OVERDUE' AND """ + OVERDUE + """
             )
             OR (:state = 'TO_DO' AND """ + TO_DO + """
             ))
            """;

    /** "Мои задачи": the user's own visible tasks, filtered by state and (optionally) course; sorted by the caller. */
    @Query(value = TaskResponse.SELECT + "WHERE t.user.id = :userId AND (:courseId IS NULL OR c.id = :courseId) AND "
            + ENROLLED + " AND " + MY_STATE,
            countQuery = "SELECT COUNT(t) FROM Task t LEFT JOIN t.course c LEFT JOIN t.assignment a "
                    + "WHERE t.user.id = :userId AND (:courseId IS NULL OR c.id = :courseId) AND " + ENROLLED
                    + " AND " + MY_STATE)
    Page<TaskResponse> findMyViews(@Param("userId") Long userId, @Param("state") String state,
                                   @Param("courseId") Long courseId, @Param("now") LocalDateTime now,
                                   Pageable pageable);

    /** The numbers above "Мои задачи": "К сдаче: N · Просрочено: M · Оценено: K", over the same tasks. */
    @Query("""
            SELECT new com.university.docassistant.dto.TaskDtos$TaskSummary(
                COALESCE(SUM(CASE WHEN """ + TO_DO + """
                 THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN """ + OVERDUE + """
                 THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN t.status = com.university.docassistant.entity.TaskStatus.GRADED THEN 1 ELSE 0 END), 0))
            FROM Task t LEFT JOIN t.assignment a WHERE t.user.id = :userId AND """ + ENROLLED)
    TaskSummary summarizeMine(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** JPQL condition on Task t: the student still has to do it (not completed, submitted or graded). */
    String UNFINISHED = """
            t.status NOT IN (com.university.docassistant.entity.TaskStatus.COMPLETED,
                             com.university.docassistant.entity.TaskStatus.SUBMITTED,
                             com.university.docassistant.entity.TaskStatus.GRADED)
            """;

    /** Unfinished, deadline from {@code from} on, nearest first: the "Upcoming deadlines" widget. */
    @Query(value = TaskResponse.SELECT + "WHERE t.user.id = :userId AND " + UNFINISHED + """
              AND t.deadline >= :from AND """ + ENROLLED + """
            ORDER BY t.deadline
            """,
            countQuery = "SELECT COUNT(t) FROM Task t WHERE t.user.id = :userId AND " + UNFINISHED + """
              AND t.deadline >= :from AND """ + ENROLLED)
    Page<TaskResponse> findUpcomingViews(@Param("userId") Long userId,
                                         @Param("from") LocalDateTime from,
                                         Pageable pageable);

    @Query(TaskResponse.SELECT + "WHERE t.id = :id")
    Optional<TaskResponse> findViewById(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Task t SET t.status = :status WHERE t.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") TaskStatus status);

    /**
     * The student's own move on an assignment task: only while it is still PENDING or IN_PROGRESS, so a submission
     * that lands at the same moment is never overwritten. Returns 0 when the task is already SUBMITTED or GRADED.
     */
    @Modifying
    @Query("""
            UPDATE Task t SET t.status = :status WHERE t.id = :id
              AND t.status IN (com.university.docassistant.entity.TaskStatus.PENDING,
                               com.university.docassistant.entity.TaskStatus.IN_PROGRESS)
            """)
    int updateStudentStatus(@Param("id") Long id, @Param("status") TaskStatus status);

    /**
     * A hand-in came in at {@code now}: the task is SUBMITTED, with the time of this current version and whether it
     * is {@code late}. Never for a GRADED task (returns 0): graded work is not handed in again.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Task t SET t.status = com.university.docassistant.entity.TaskStatus.SUBMITTED,
                t.submittedAt = :now, t.late = :late
            WHERE t.id = :id AND t.status <> com.university.docassistant.entity.TaskStatus.GRADED
            """)
    int markSubmitted(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("late") boolean late);

    /** What deciding whether a hand-in is accepted needs: the task's status and deadline, its assignment's rules. */
    record SubmitRules(TaskStatus status, LocalDateTime deadline, boolean allowLate, AssignmentStatus assignmentStatus) {
    }

    @Query("""
            SELECT new com.university.docassistant.repository.TaskRepository$SubmitRules(
                t.status, t.deadline, a.allowLate, a.status)
            FROM Task t JOIN t.assignment a WHERE t.id = :id
            """)
    Optional<SubmitRules> findSubmitRulesById(@Param("id") Long id);

    /** What grading a task needs to know: its course, assignment, state, the assignment's max score, its student. */
    record GradeTarget(Long taskId, Long courseId, Long assignmentId, TaskStatus status, LocalDateTime submittedAt,
                       BigDecimal score, Integer maxScore, String studentEmail) {
    }

    @Query("""
            SELECT new com.university.docassistant.repository.TaskRepository$GradeTarget(
                t.id, c.id, a.id, t.status, t.submittedAt, t.score, a.maxScore, u.email)
            FROM Task t JOIN t.user u LEFT JOIN t.course c LEFT JOIN t.assignment a WHERE t.id = :id
            """)
    Optional<GradeTarget> findGradeTargetById(@Param("id") Long id);

    /** The teacher's grade: GRADED, with score, feedback, when and by whom. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Task t SET t.status = com.university.docassistant.entity.TaskStatus.GRADED, t.score = :score,
                t.feedback = :feedback, t.gradedAt = :gradedAt, t.gradedBy = :grader
            WHERE t.id = :id
            """)
    int grade(@Param("id") Long id, @Param("score") BigDecimal score, @Param("feedback") String feedback,
              @Param("gradedAt") LocalDateTime gradedAt, @Param("grader") User grader);

    /** Takes the grade back: the task returns to {@code status} (SUBMITTED or PENDING) without score or feedback. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Task t SET t.status = :status, t.score = NULL, t.feedback = NULL, t.gradedAt = NULL,
                t.gradedBy = NULL
            WHERE t.id = :id
            """)
    int clearGrade(@Param("id") Long id, @Param("status") TaskStatus status);

    /** One row of the grading page: an enrolled student's task, without its files (see TaskSubmissionRepository). */
    record GradingRow(Long taskId, Long studentId, String studentFullName, String studentEmail, TaskStatus status,
                      LocalDateTime submittedAt, boolean late, BigDecimal score, String feedback,
                      LocalDateTime gradedAt) {
    }

    /** JPQL condition on Task t for the grading page's filter (GradingFilter name in :filter). */
    String GRADING_FILTER = """
            (:filter = 'ALL'
             OR (:filter = 'NOT_SUBMITTED' AND t.submittedAt IS NULL
                 AND t.status <> com.university.docassistant.entity.TaskStatus.GRADED)
             OR (:filter = 'SUBMITTED' AND t.status = com.university.docassistant.entity.TaskStatus.SUBMITTED)
             OR (:filter = 'LATE' AND t.late = true)
             OR (:filter = 'GRADED' AND t.status = com.university.docassistant.entity.TaskStatus.GRADED))
            """;

    /** The assignment's tasks of students still enrolled, by name; one page. */
    @Query(value = """
            SELECT new com.university.docassistant.repository.TaskRepository$GradingRow(
                t.id, u.id, u.fullName, u.email, t.status, t.submittedAt, t.late, t.score, t.feedback, t.gradedAt)
            FROM Task t JOIN t.user u
            WHERE t.assignment.id = :assignmentId AND """ + ENROLLED + " AND " + GRADING_FILTER + """
            ORDER BY LOWER(u.fullName), u.email, t.id
            """,
            countQuery = "SELECT COUNT(t) FROM Task t WHERE t.assignment.id = :assignmentId AND " + ENROLLED
                    + " AND " + GRADING_FILTER)
    Page<GradingRow> findGradingRows(@Param("assignmentId") Long assignmentId, @Param("filter") String filter,
                                     Pageable pageable);

    @Query("SELECT new com.university.docassistant.repository.TaskRepository$GradingRow("
            + "t.id, u.id, u.fullName, u.email, t.status, t.submittedAt, t.late, t.score, t.feedback, t.gradedAt) "
            + "FROM Task t JOIN t.user u WHERE t.id = :id")
    Optional<GradingRow> findGradingRowById(@Param("id") Long id);

    /** Counts for the progress line, over the same students as {@link #findGradingRows}. */
    @Query("""
            SELECT new com.university.docassistant.dto.GradingDtos$GradingSummary(
                COUNT(t),
                COALESCE(SUM(CASE WHEN t.submittedAt IS NOT NULL THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN t.late = true THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN t.status = com.university.docassistant.entity.TaskStatus.GRADED THEN 1 ELSE 0 END), 0))
            FROM Task t WHERE t.assignment.id = :assignmentId AND """ + ENROLLED)
    GradingSummary summarizeGrading(@Param("assignmentId") Long assignmentId);

    /** What the student's "работа оценена" notification says. */
    record GradedNotice(Long userId, String title, BigDecimal score, Integer maxScore, String courseCode) {
    }

    @Query("""
            SELECT new com.university.docassistant.repository.TaskRepository$GradedNotice(
                t.user.id, t.title, t.score, a.maxScore, c.code)
            FROM Task t LEFT JOIN t.assignment a LEFT JOIN t.course c WHERE t.id = :id
            """)
    Optional<GradedNotice> findGradedNoticeById(@Param("id") Long id);

    /**
     * JPQL condition on Task t: the 24-hour reminder is due. Nothing handed in, the deadline in (:now, :until], the
     * assignment ACTIVE, the student still enrolled, no reminder sent yet.
     */
    String REMINDER_DUE = """
            t.reminderSentAt IS NULL
            AND t.status IN (com.university.docassistant.entity.TaskStatus.PENDING,
                             com.university.docassistant.entity.TaskStatus.IN_PROGRESS)
            AND t.deadline > :now AND t.deadline <= :until
            AND t.assignment.id IN (SELECT ra.id FROM Assignment ra
                                    WHERE ra.status = com.university.docassistant.entity.AssignmentStatus.ACTIVE)
            AND EXISTS (SELECT e.enrolledAt FROM Enrollment e WHERE e.course = t.course AND e.student = t.user)
            """;

    /** One batch of tasks with a due reminder, after {@code afterId} by id (keyset, so every run moves forward). */
    @Query("SELECT t.id FROM Task t WHERE t.id > :afterId AND " + REMINDER_DUE + " ORDER BY t.id")
    List<Long> findReminderDueIds(@Param("afterId") Long afterId, @Param("now") LocalDateTime now,
                                  @Param("until") LocalDateTime until, Pageable batch);

    /**
     * Claims the task's reminder: 1 for exactly one caller, 0 for everyone else (another run or instance claimed it,
     * or the task is no longer due). Only the caller that got 1 sends.
     */
    @Modifying
    @Query("UPDATE Task t SET t.reminderSentAt = :now WHERE t.id = :id AND " + REMINDER_DUE)
    int claimReminder(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    /** What the reminder says. */
    record ReminderNotice(Long taskId, Long userId, String title, LocalDateTime deadline, String courseCode) {
    }

    @Query("""
            SELECT new com.university.docassistant.repository.TaskRepository$ReminderNotice(
                t.id, t.user.id, t.title, t.deadline, c.code)
            FROM Task t LEFT JOIN t.course c WHERE t.id IN :ids ORDER BY t.id
            """)
    List<ReminderNotice> findReminderNotices(@Param("ids") Collection<Long> ids);

    /**
     * Locks the task's row until the transaction ends, so two hand-ins of the same task (or a hand-in and the
     * teacher's grade) take turns: the version number and the GRADED check stay right.
     */
    @Query(value = "SELECT id FROM tasks WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockById(@Param("id") Long id);

    @Query("SELECT t.user.id FROM Task t WHERE t.id = :id")
    Optional<Long> findUserIdById(@Param("id") Long id);

    /** Owner, course and assignment of a task: what deciding who may submit to or read it needs. */
    record TaskAccess(Long id, Long userId, Long courseId, Long assignmentId) {
    }

    @Query("""
            SELECT new com.university.docassistant.repository.TaskRepository$TaskAccess(t.id, t.user.id, c.id, a.id)
            FROM Task t LEFT JOIN t.course c LEFT JOIN t.assignment a WHERE t.id = :id
            """)
    Optional<TaskAccess> findAccessById(@Param("id") Long id);

    /**
     * Gives every student enrolled in the course a PENDING task for each ACTIVE assignment they do not have yet.
     * Idempotent; returns the number of tasks created.
     */
    @Modifying
    @Query(value = """
            INSERT INTO tasks (title, description, deadline, status, document_id, course_id, user_id, assignment_id)
            SELECT a.title, a.description, a.deadline, 'PENDING', a.source_document_id, a.course_id, e.student_id, a.id
            FROM assignments a JOIN course_students e ON e.course_id = a.course_id
            WHERE a.course_id = :courseId AND a.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.assignment_id = a.id AND t.user_id = e.student_id)
            """, nativeQuery = true)
    int fanOut(@Param("courseId") Long courseId);

    /**
     * Copies title, description and deadline of the course's assignments into their tasks; a moved deadline also
     * re-decides whether a submission was late (an extension forgives it), and a deadline moved later clears the
     * 24-hour reminder, so the new deadline is reminded of again.
     */
    @Modifying
    @Query(value = """
            UPDATE tasks t SET title = a.title, description = a.description, deadline = a.deadline,
                   late = (t.submitted_at IS NOT NULL AND a.deadline IS NOT NULL AND t.submitted_at > a.deadline),
                   reminder_sent_at = CASE WHEN a.deadline IS NULL OR a.deadline > t.deadline THEN NULL
                                           ELSE t.reminder_sent_at END
            FROM assignments a
            WHERE t.assignment_id = a.id AND a.course_id = :courseId
              AND (t.title <> a.title OR t.description IS DISTINCT FROM a.description
                   OR t.deadline IS DISTINCT FROM a.deadline)
            """, nativeQuery = true)
    int syncWithAssignments(@Param("courseId") Long courseId);

    @Modifying
    @Query("DELETE FROM Task t WHERE t.assignment.id IN :assignmentIds")
    int deleteByAssignmentIdIn(@Param("assignmentIds") Collection<Long> assignmentIds);

    /** Which of the assignments have a task with student work; those must be closed, never deleted. */
    @Query("SELECT DISTINCT t.assignment.id FROM Task t WHERE t.assignment.id IN :assignmentIds AND " + ACTIVITY)
    List<Long> findAssignmentIdsWithActivity(@Param("assignmentIds") Collection<Long> assignmentIds);

    /** The tasks of kept assignments lose the link to a document that is being deleted. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Task t SET t.document = NULL WHERE t.assignment.id IN :assignmentIds")
    int detachDocumentFromAssignmentTasks(@Param("assignmentIds") Collection<Long> assignmentIds);

    /** Tasks with student work (also old ones without an assignment) survive the deletion of their documents. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Task t SET t.document = NULL WHERE t.document.id IN :documentIds AND " + ACTIVITY)
    int detachDocumentFromTasksWithActivity(@Param("documentIds") Collection<Long> documentIds);

    @Modifying
    @Query("DELETE FROM Task t WHERE t.assignment.id IN (SELECT a.id FROM Assignment a WHERE a.sourceDocument.id = :documentId)")
    int deleteByAssignmentSourceDocumentId(@Param("documentId") Long documentId);

    @Modifying
    @Query("""
            DELETE FROM Task t WHERE t.assignment.id IN (
                SELECT a.id FROM Assignment a WHERE a.sourceDocument.owner.id = :ownerId)
            """)
    int deleteByAssignmentSourceDocumentOwnerId(@Param("ownerId") Long ownerId);

    /** Not used on unenroll any more: a student who leaves keeps their tasks and submissions (see {@link #ENROLLED}). */
    @Modifying
    @Query("DELETE FROM Task t WHERE t.course.id = :courseId AND t.user.id = :userId")
    int deleteByCourseIdAndUserId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM Task t WHERE t.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM Task t WHERE t.document.id = :documentId")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    @Modifying
    @Query("DELETE FROM Task t WHERE t.document.id IN (SELECT d.id FROM Document d WHERE d.owner.id = :ownerId)")
    int deleteByDocumentOwnerId(@Param("ownerId") Long ownerId);
}
