package com.university.docassistant.config;

import com.university.docassistant.entity.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Moves existing tasks to the honest submission lifecycle (US-10). Idempotent, in one transaction:
 * <ol>
 *   <li>rebuilds the check constraint of tasks.status from {@link TaskStatus} (ddl-auto=update never alters it, so
 *       SUBMITTED and GRADED would be rejected);</li>
 *   <li>assignment tasks with a submission become SUBMITTED, with submitted_at the latest submission's time and late
 *       when that is after the deadline; only rows without submitted_at, so it runs once per task;</li>
 *   <li>assignment tasks marked COMPLETED by hand without any submission go back to IN_PROGRESS.</li>
 * </ol>
 * Legacy tasks without an assignment and everything else keep their status.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskSubmissionStatusMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String values = Arrays.stream(TaskStatus.values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
        jdbcTemplate.execute("ALTER TABLE tasks DROP CONSTRAINT IF EXISTS tasks_status_check");
        jdbcTemplate.execute("ALTER TABLE tasks ADD CONSTRAINT tasks_status_check CHECK (status IN (" + values + "))");

        int submitted = jdbcTemplate.update("""
                UPDATE tasks t SET status = 'SUBMITTED',
                       submitted_at = (SELECT MAX(s.submitted_at) FROM task_submissions s WHERE s.task_id = t.id),
                       late = (t.deadline IS NOT NULL
                               AND (SELECT MAX(s.submitted_at) FROM task_submissions s WHERE s.task_id = t.id) > t.deadline)
                WHERE t.assignment_id IS NOT NULL
                  AND t.submitted_at IS NULL
                  AND t.status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED')
                  AND EXISTS (SELECT 1 FROM task_submissions s WHERE s.task_id = t.id)
                """);
        int reopened = jdbcTemplate.update("""
                UPDATE tasks t SET status = 'IN_PROGRESS'
                WHERE t.assignment_id IS NOT NULL AND t.status = 'COMPLETED'
                  AND NOT EXISTS (SELECT 1 FROM task_submissions s WHERE s.task_id = t.id)
                """);
        if (submitted + reopened > 0) {
            log.info("Task statuses migrated: {} task(s) with submissions -> SUBMITTED, "
                    + "{} completed task(s) without submissions -> IN_PROGRESS", submitted, reopened);
        }
    }
}
