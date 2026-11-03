package com.university.docassistant.config;

import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The AI draft step is gone (changed at the course teacher's request): every assignment is ACTIVE or CLOSED.
 * Idempotent, in one transaction, before the other startup jobs (nothing may load a row with the old status):
 * <ol>
 *   <li>assignments still in the old DRAFT status become ACTIVE, and their courses' enrolled students get the tasks
 *       (no notifications: they are old drafts, not new work);</li>
 *   <li>the column of the old "looks like" mark of drafts, assignments.duplicate_of_id, is dropped;</li>
 *   <li>the check constraint of assignments.status is rebuilt from {@link AssignmentStatus} (ddl-auto=update never
 *       alters it).</li>
 * </ol>
 * A second start finds nothing to convert and rebuilds the same constraint.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class AssignmentStatusMigration implements ApplicationRunner {

    /** The status AI assignments had before they were published at once. */
    static final String OLD_DRAFT_STATUS = "DRAFT";

    private final JdbcTemplate jdbcTemplate;
    private final TaskRepository taskRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Long> courseIds = jdbcTemplate.queryForList(
                "UPDATE assignments SET status = 'ACTIVE' WHERE status = ? RETURNING course_id", Long.class,
                OLD_DRAFT_STATUS);
        if (!courseIds.isEmpty()) {
            int handedOut = 0;
            for (Long courseId : courseIds.stream().distinct().toList()) {
                taskRepository.syncWithAssignments(courseId);
                handedOut += taskRepository.fanOut(courseId);
            }
            log.info("Drafts removed: {} draft assignment(s) published, {} student task(s) handed out",
                    courseIds.size(), handedOut);
        }
        jdbcTemplate.execute("ALTER TABLE assignments DROP COLUMN IF EXISTS duplicate_of_id");
        String values = Arrays.stream(AssignmentStatus.values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
        jdbcTemplate.execute("ALTER TABLE assignments DROP CONSTRAINT IF EXISTS assignments_status_check");
        jdbcTemplate.execute("ALTER TABLE assignments ADD CONSTRAINT assignments_status_check CHECK (status IN ("
                + values + "))");
        log.debug("assignments.status accepts {}", values);
    }
}
