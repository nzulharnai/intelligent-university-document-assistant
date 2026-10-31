package com.university.docassistant.service;

import com.university.docassistant.config.AsyncConfig;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentRepository.AnalysisSource;
import com.university.docassistant.service.AiTaskExtractionService.Analysis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Background AI analysis of a document: summarizes its extracted text, lists key topics, and turns the assignments,
 * labs and deadlines it finds into assignments of the document's course, which every enrolled student then gets as
 * a task (see AssignmentService).
 * The AI call runs outside any transaction (it can take a minute); only the final write is transactional.
 * Failures are logged and never propagate: a document is always kept, with or without tasks.
 * A long text analyzed only in part (a chunk failed) still saves what was found and is marked as partial; its
 * existing assignments the AI did not return are then kept, as they may sit in the part that failed.
 * EXAM_ANSWERS documents are never analyzed, however the analysis was requested.
 */
@Slf4j
@Service
public class DocumentAnalysisService {

    /** Categories analyzed automatically on upload; others only on an explicit request. */
    public static final Set<DocumentCategory> AUTO_ANALYZED = Set.of(DocumentCategory.ASSIGNMENT, DocumentCategory.SYLLABUS);

    private final DocumentRepository documentRepository;
    private final AssignmentService assignmentService;
    private final AiTaskExtractionService extractionService;
    private final TransactionTemplate transactionTemplate;

    public DocumentAnalysisService(DocumentRepository documentRepository, AssignmentService assignmentService,
                                   AiTaskExtractionService extractionService,
                                   PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.assignmentService = assignmentService;
        this.extractionService = extractionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Automatic analysis after upload; skipped unless the category is in {@link #AUTO_ANALYZED}. */
    @Async(AsyncConfig.AI_EXECUTOR)
    public void analyzeAndCreateTasksAsync(Long documentId) {
        analyzeNow(documentId, true);
    }

    /** Analysis requested by a user; runs for any category and replaces the document's previous assignments. */
    @Async(AsyncConfig.AI_EXECUTOR)
    public void reanalyzeAsync(Long documentId) {
        analyzeNow(documentId, false);
    }

    /**
     * The analysis itself, in the caller's thread. For code that already runs on the AI executor
     * (DocumentProcessingService): submitting an @Async job from there could wait on the same full queue.
     * {@code automatic} skips categories outside {@link #AUTO_ANALYZED}. Never throws.
     */
    public void analyzeNow(Long documentId, boolean automatic) {
        try {
            AnalysisSource source = documentRepository.findAnalysisSource(documentId).orElse(null);
            if (source == null) {
                log.info("AI analysis skipped: document {} no longer exists", documentId);
                return;
            }
            if (source.category() == DocumentCategory.EXAM_ANSWERS) {
                // Never turned into assignments: their titles and deadlines would reach students as tasks.
                log.info("AI analysis skipped for document {}: EXAM_ANSWERS are never analyzed", documentId);
                return;
            }
            if (automatic && !AUTO_ANALYZED.contains(source.category())) {
                log.debug("AI analysis skipped for document {}: category {}", documentId, source.category());
                return;
            }
            if (source.maskedText() == null) {
                log.info("AI analysis skipped for document {}: no extracted text", documentId);
                return;
            }

            long started = System.currentTimeMillis();
            Optional<Analysis> analysis = extractionService.analyze(source.maskedText());
            if (analysis.isEmpty()) {
                // Unavailable AI or an unusable answer: the previous summary and assignments are kept, not wiped.
                log.info("AI analysis of document {} produced no result ({} ms)", documentId,
                        System.currentTimeMillis() - started);
                return;
            }
            int saved = saveAnalysis(source, analysis.get());
            log.info("AI analysis of document {} found {} assignment(s){} ({} ms)", documentId, saved,
                    analysis.get().partial() ? ", partial: some parts of the text got no answer" : "",
                    System.currentTimeMillis() - started);
        } catch (RuntimeException ex) {
            log.warn("AI analysis of document {} failed", documentId, ex);
        }
    }

    /** Stores summary and topics and replaces the document's assignments; returns the number of assignments. */
    private int saveAnalysis(AnalysisSource source, Analysis analysis) {
        Integer saved = transactionTemplate.execute(status -> {
            // Locked so a concurrent analysis of the same document waits instead of interleaving its assignments;
            // a document deleted meanwhile is detected here and gets none.
            if (documentRepository.lockById(source.id()).isEmpty()) {
                return 0;
            }
            documentRepository.updateAnalysis(source.id(), analysis.summary(),
                    analysis.topics().isEmpty() ? null : String.join("\n", analysis.topics()),
                    analysis.partial(), LocalDateTime.now());
            if (source.courseId() == null) {
                return 0;
            }
            return assignmentService.replaceFromDocument(source.id(), source.courseId(), analysis.tasks(),
                    !analysis.partial());
        });
        return saved == null ? 0 : saved;
    }
}
