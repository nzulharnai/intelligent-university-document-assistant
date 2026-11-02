package com.university.docassistant.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills tasks.deadline_before_enrollment for tasks that existed before the column. ddl-auto=update adds the column
 * as false for every row; this sets it to true for assignment tasks whose deadline is before the student's
 * course_students.enrolled_at (no enrollment row or no enrolled_at: stays false).
 * <p>
 * Runs once per database: afterwards the column's comment holds {@link #DONE_MARK} and later starts skip it, so a
 * restart never sets the flag again on a task where the teacher's deadline change has cleared it (the deadline sync
 * only ever clears it). In one transaction with the mark.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadlineBeforeEnrollmentMigration implements ApplicationRunner {

    static final String DONE_MARK = "backfilled from course_students.enrolled_at (v1)";

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String comment = jdbcTemplate.queryForObject(
                "SELECT col_description('tasks'::regclass, a.attnum) FROM pg_attribute a "
                        + "WHERE a.attrelid = 'tasks'::regclass AND a.attname = 'deadline_before_enrollment'",
                String.class);
        if (DONE_MARK.equals(comment)) {
            return;
        }
        int flagged = jdbcTemplate.update("""
                UPDATE tasks t SET deadline_before_enrollment = true
                FROM course_students e
                WHERE e.course_id = t.course_id AND e.student_id = t.user_id
                  AND t.assignment_id IS NOT NULL
                  AND t.deadline IS NOT NULL AND e.enrolled_at IS NOT NULL
                  AND t.deadline < e.enrolled_at
                  AND t.deadline_before_enrollment = false
                """);
        jdbcTemplate.execute("COMMENT ON COLUMN tasks.deadline_before_enrollment IS '" + DONE_MARK + "'");
        log.info("tasks.deadline_before_enrollment backfilled: {} task(s) whose deadline passed before enrollment",
                flagged);
    }
}
