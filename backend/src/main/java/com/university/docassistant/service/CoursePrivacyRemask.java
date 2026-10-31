package com.university.docassistant.service;

import com.university.docassistant.config.AsyncConfig;
import com.university.docassistant.config.DocumentPrivacyBackfill;
import com.university.docassistant.repository.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;

/**
 * US-09: masks a course's documents again when what stays readable in them changes - the course got another teacher
 * or the teacher changed their phone (e-mails cannot be changed). Their masked copies are marked outdated in the
 * caller's transaction; after commit DocumentPrivacyBackfill re-masks them and rebuilds their chunks on the privacy
 * thread, never in the request. If that never runs (queue full, server stopped), the startup backfill does it.
 */
@Slf4j
@Service
public class CoursePrivacyRemask {

    private final DocumentRepository documentRepository;
    private final DocumentPrivacyBackfill backfill;
    private final TaskExecutor executor;

    public CoursePrivacyRemask(DocumentRepository documentRepository, DocumentPrivacyBackfill backfill,
                               @Qualifier(AsyncConfig.PRIVACY_EXECUTOR) TaskExecutor executor) {
        this.documentRepository = documentRepository;
        this.backfill = backfill;
        this.executor = executor;
    }

    /** Marks the courses' documents and re-masks them in the background once the caller's transaction commits. */
    @Transactional
    public void schedule(Collection<Long> courseIds) {
        if (courseIds.isEmpty()) {
            return;
        }
        List<Long> ids = List.copyOf(courseIds);
        int marked = documentRepository.markPrivacyOutdatedInCourses(ids);
        if (marked == 0) {
            return;
        }
        Runnable job = () -> {
            try {
                int done = backfill.backfillCourses(ids);
                log.info("Re-masked {} document(s) of course(s) {} after their teacher's contacts changed", done, ids);
            } catch (RuntimeException ex) {
                log.warn("Re-masking the documents of course(s) {} failed; the startup backfill will redo it", ids, ex);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(job, ids);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(job, ids);
            }
        });
    }

    private void submit(Runnable job, List<Long> ids) {
        try {
            executor.execute(job);
        } catch (TaskRejectedException ex) {
            log.warn("Re-masking of course(s) {} not queued (queue full); it runs at the next start", ids);
        }
    }
}
