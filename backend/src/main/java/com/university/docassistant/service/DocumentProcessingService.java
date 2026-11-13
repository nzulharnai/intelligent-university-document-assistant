package com.university.docassistant.service;

import com.university.docassistant.config.AsyncConfig;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentType;
import com.university.docassistant.repository.DocumentClassificationRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentRepository.ClassificationSource;
import com.university.docassistant.service.DocumentClassificationService.ClassificationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * Background AI classification of documents uploaded without a category (US-05), so an upload never waits for the
 * AI. Pipeline per document, on the AI executor:
 * <ol>
 *   <li>claim it: PENDING -> PROCESSING in a short transaction; any other status means another job has it or it is
 *       finished, and nothing happens, so duplicate submissions are harmless;</li>
 *   <li>classify its stored text outside any transaction (the AI call can take a while);</li>
 *   <li>in one transaction: category and type, rule A (EXAM_ANSWERS -> TEACHER_ONLY), the stored AI result, DONE;
 *       or FAILED with category OTHER when the AI gave no answer;</li>
 *   <li>for SYLLABUS/ASSIGNMENT with text, the deadline analysis, synchronously in this same thread.</li>
 * </ol>
 * Students do not see the document until step 3 has committed (rule C, AccessControlService). Never throws: an
 * unexpected error marks the document FAILED in a new transaction.
 */
@Slf4j
@Service
public class DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final DocumentClassificationRepository classificationRepository;
    private final DocumentClassificationService classificationService;
    private final DocumentAnalysisService analysisService;
    private final AuditService auditService;
    private final DocumentOcrService ocrService;
    private final TransactionTemplate transaction;
    private final TransactionTemplate newTransaction;

    public DocumentProcessingService(DocumentRepository documentRepository,
                                     DocumentClassificationRepository classificationRepository,
                                     DocumentClassificationService classificationService,
                                     DocumentAnalysisService analysisService,
                                     AuditService auditService,
                                     PlatformTransactionManager transactionManager,
                                     DocumentOcrService ocrService) {
        this.documentRepository = documentRepository;
        this.classificationRepository = classificationRepository;
        this.classificationService = classificationService;
        this.analysisService = analysisService;
        this.auditService = auditService;
        this.ocrService = ocrService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Queues {@link #process}; throws TaskRejectedException when the AI queue is full (the document stays PENDING). */
    @Async(AsyncConfig.AI_EXECUTOR)
    public void processAsync(Long documentId) {
        process(documentId);
    }

    /**
     * US-07: OCR for a scan whose category is already known (chosen by hand, or no AI to classify it), then the
     * deadline analysis of a syllabus or an assignment, as an upload with text would get it. Throws
     * TaskRejectedException when the AI queue is full (the document stays queued for OCR until the next start).
     */
    @Async(AsyncConfig.AI_EXECUTOR)
    public void ocrAsync(Long documentId) {
        if (ocrService.runIfPending(documentId) == DocumentOcrService.Outcome.DONE) {
            analysisService.analyzeNow(documentId, true);
        }
    }

    /** Whether OCR can run on this server at all. */
    public boolean ocrAvailable() {
        return ocrService.isAvailable();
    }

    /** Whether an upload with this type and extracted text must be queued for OCR; see DocumentOcrService. */
    public boolean needsOcr(String contentType, String extractedText) {
        return ocrService.needsOcr(contentType, extractedText);
    }

    /**
     * Classifies one PENDING document in the caller's thread; does nothing for any other status. Never throws.
     * Order: OCR of a scan (if queued) -> masking and chunks (stored with the recognized text) -> classification ->
     * deadlines (published as assignments at once).
     */
    public void process(Long documentId) {
        DocumentCategory finalCategory;
        boolean hasText;
        try {
            Integer claimed = transaction.execute(status ->
                    documentRepository.changeAiStatus(documentId, AiStatus.PENDING, AiStatus.PROCESSING));
            if (claimed == null || claimed == 0) {
                log.debug("Classification of document {} skipped: not PENDING", documentId);
                return;
            }
            // A scan gets its text first, so the classifier and the analysis see it (never throws).
            ocrService.runIfPending(documentId);
            ClassificationSource source = documentRepository.findClassificationSource(documentId).orElse(null);
            if (source == null) {
                log.info("Classification skipped: document {} no longer exists", documentId);
                return;
            }
            hasText = source.maskedText() != null;

            long started = System.currentTimeMillis();
            Optional<ClassificationResult> result =
                    classificationService.classify(source.originalFileName(), source.maskedText());
            finalCategory = transaction.execute(status -> store(source, result.orElse(null)));
            log.info("Document {} classified as {} in background ({} ms)", documentId,
                    finalCategory == null ? "(deleted meanwhile)" : finalCategory + (result.isEmpty() ? ", FAILED" : ""),
                    System.currentTimeMillis() - started);
        } catch (RuntimeException ex) {
            log.warn("Background classification of document {} failed", documentId, ex);
            markFailed(documentId);
            return;
        }

        // Outside the try above: the classification is committed, so a failure here must not turn it into FAILED.
        // analyzeNow never throws and runs here, on the AI executor, instead of queueing another job.
        if (hasText && finalCategory != null && DocumentAnalysisService.AUTO_ANALYZED.contains(finalCategory)) {
            analysisService.analyzeNow(documentId, true);
        }
    }

    /** Step 3, inside one transaction; the final category, or null when the document was deleted meanwhile. */
    private DocumentCategory store(ClassificationSource source, ClassificationResult result) {
        Long id = source.id();
        if (result == null) {
            if (documentRepository.updateClassification(id, DocumentCategory.OTHER, DocumentType.OTHER,
                    AiStatus.FAILED) == 0) {
                return null;
            }
            // An older AI result (from before a reclassify) would no longer match the category.
            classificationRepository.findByDocumentId(id).ifPresent(classificationRepository::delete);
            return DocumentCategory.OTHER;
        }
        DocumentCategory category = result.category();
        if (documentRepository.updateClassification(id, category, DocumentType.fromCategory(category),
                AiStatus.DONE) == 0) {
            return null;
        }
        boolean forced = category == DocumentCategory.EXAM_ANSWERS && documentRepository.forceTeacherOnly(id) > 0;
        classificationService.saveResult(id, result);
        auditService.record(source.ownerEmail(), AuditService.DOCUMENT_CLASSIFIED,
                "Document " + id + " classified by AI as " + category
                        + (result.confidence() == null ? "" : " (confidence " + result.confidence() + ")")
                        + (forced ? ", visibility forced to TEACHER_ONLY" : ""));
        return category;
    }

    private void markFailed(Long documentId) {
        try {
            newTransaction.executeWithoutResult(status -> documentRepository.setAiStatus(documentId, AiStatus.FAILED));
        } catch (RuntimeException ex) {
            log.error("Could not mark document {} as FAILED", documentId, ex);
        }
    }
}
