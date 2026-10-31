package com.university.docassistant.config;

import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentRepository.PrivacySource;
import com.university.docassistant.service.DocumentChunkService;
import com.university.docassistant.service.DocumentParserService;
import com.university.docassistant.service.DocumentPrivacyService;
import com.university.docassistant.util.PersonalDataMasker.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;

/**
 * US-09: gives every document with text a masked copy made with the current settings (documents.pii_scan differs
 * from DocumentPrivacyService.fingerprint()): masked_text, pii_count, pii_types, a snippet and Q&A chunks rebuilt
 * from it (text_tsv follows masked_text by itself, see DocumentSearchSchema). Idempotent: a second run finds nothing
 * to do. Runs before the other startup jobs (Order 0), so AiProcessingRecovery never queues a document whose text is
 * not masked yet. Small batches, one transaction per document, so texts are never all in memory at once.
 * {@link #backfillCourses} does the same for the documents of some courses only (CoursePrivacyRemask).
 */
@Slf4j
@Component
@Order(0)
public class DocumentPrivacyBackfill implements ApplicationRunner {

    static final int BATCH_SIZE = 50;

    private final DocumentRepository documentRepository;
    private final DocumentPrivacyService privacyService;
    private final DocumentChunkService chunkService;
    private final TransactionTemplate transaction;

    public DocumentPrivacyBackfill(DocumentRepository documentRepository, DocumentPrivacyService privacyService,
                                   DocumentChunkService chunkService, PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.privacyService = privacyService;
        this.chunkService = chunkService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        backfill();
    }

    /** Returns how many documents got a new masked copy. */
    public int backfill() {
        String fingerprint = privacyService.fingerprint();
        return run(fingerprint, (afterId, page) -> documentRepository.findPrivacyBatch(fingerprint, afterId, page));
    }

    /** Only the documents of these courses whose masked copy is outdated; returns how many got a new one. */
    public int backfillCourses(Collection<Long> courseIds) {
        if (courseIds.isEmpty()) {
            return 0;
        }
        String fingerprint = privacyService.fingerprint();
        return run(fingerprint, (afterId, page) ->
                documentRepository.findPrivacyBatchInCourses(fingerprint, courseIds, afterId, page));
    }

    private int run(String fingerprint, BiFunction<Long, PageRequest, List<PrivacySource>> nextBatch) {
        int documents = 0;
        int items = 0;
        long lastId = 0;
        while (true) {
            List<PrivacySource> batch = nextBatch.apply(lastId, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            for (PrivacySource source : batch) {
                if (fingerprint.equals(source.piiScan())) {
                    continue; // Done already (the query skips these; kept so a re-run can never redo work).
                }
                Result masked = privacyService.mask(source.extractedText(), source.teacherEmail(),
                        source.teacherPhone());
                transaction.executeWithoutResult(status -> {
                    documentRepository.updatePrivacy(source.id(), masked.text(),
                            DocumentParserService.snippet(masked.text()), masked.count(), masked.typesText(),
                            fingerprint);
                    chunkService.index(source.id(), masked.text());
                });
                documents++;
                items += masked.count();
            }
            lastId = batch.get(batch.size() - 1).id();
        }
        if (documents > 0) {
            log.info("Masked the texts of {} document(s) ({} personal data item(s) hidden) and rebuilt their chunks",
                    documents, items);
        }
        return documents;
    }
}
