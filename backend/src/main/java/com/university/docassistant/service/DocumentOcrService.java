package com.university.docassistant.service;

import com.university.docassistant.entity.OcrStatus;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentRepository.OcrSource;
import com.university.docassistant.service.DocumentPrivacyService.PreparedText;
import com.university.docassistant.service.OcrEngine.OcrException;
import com.university.docassistant.service.OcrEngine.Recognized;
import com.university.docassistant.service.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;

/**
 * US-07: text for scanned PDFs and images. Decided at upload ({@link #needsOcr}), done later by the background job
 * ({@link #runIfPending}); never in the upload request. Recognized text goes through the same path as extracted
 * text: masked copy (US-09), snippet, Q&A chunks, and text_tsv (generated from masked_text).
 */
@Slf4j
@Service
public class DocumentOcrService {

    /** Less text than this (scans often have a few stray characters) means the file needs OCR. */
    static final int MIN_TEXT_LENGTH = 100;

    public enum Outcome {
        /** Not queued for OCR, or another job took it. */
        NOT_NEEDED,
        /** OCR is switched off or Tesseract is missing: the document stays queued for a later start. */
        UNAVAILABLE,
        DONE,
        FAILED
    }

    private final OcrEngine engine;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final DocumentPrivacyService privacyService;
    private final DocumentChunkService chunkService;
    private final TransactionTemplate transaction;

    public DocumentOcrService(OcrEngine engine, DocumentRepository documentRepository, StorageService storageService,
                              DocumentPrivacyService privacyService, DocumentChunkService chunkService,
                              PlatformTransactionManager transactionManager) {
        this.engine = engine;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.privacyService = privacyService;
        this.chunkService = chunkService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** OCR is switched on and Tesseract with every configured language was found. */
    public boolean isAvailable() {
        return engine.isAvailable();
    }

    /** A PDF or an image whose extracted text is missing or almost empty, and OCR can run. */
    public boolean needsOcr(String contentType, String extractedText) {
        boolean scanType = "application/pdf".equals(contentType) || (contentType != null && contentType.startsWith("image/"));
        boolean noText = extractedText == null || extractedText.strip().length() < MIN_TEXT_LENGTH;
        return scanType && noText && engine.isAvailable();
    }

    /**
     * Recognizes the document if it is queued for OCR (ocr_status PENDING) and stores the text like an upload does.
     * Runs in the caller's thread, outside any transaction while Tesseract works. Never throws: a failure, a timeout
     * or no text keep the document and mark it FAILED ("Нужна проверка").
     */
    public Outcome runIfPending(Long documentId) {
        try {
            if (!engine.isAvailable()) {
                return Outcome.UNAVAILABLE;
            }
            Integer claimed = transaction.execute(status ->
                    documentRepository.changeOcrStatus(documentId, OcrStatus.PENDING, OcrStatus.RUNNING));
            if (claimed == null || claimed == 0) {
                return Outcome.NOT_NEEDED;
            }
            OcrSource source = documentRepository.findOcrSource(documentId).orElse(null);
            if (source == null) {
                return Outcome.NOT_NEEDED; // Deleted meanwhile.
            }
            long started = System.currentTimeMillis();
            Recognized recognized;
            try (InputStream in = storageService.load(source.storedFileName()).getInputStream()) {
                recognized = engine.recognize(in.readAllBytes(), source.originalFileName(), source.contentType());
            } catch (OcrException ex) {
                return fail(documentId, ex.isTimeout() ? "timed out" : ex.getMessage());
            }
            if (recognized == null || recognized.text() == null || recognized.text().isBlank()) {
                return fail(documentId, "no text recognized");
            }
            PreparedText prepared = privacyService.prepare(recognized.text(), source.teacherEmail(),
                    source.teacherPhone());
            transaction.executeWithoutResult(status -> {
                documentRepository.updateRecognizedText(documentId, recognized.text(), recognized.truncated(),
                        prepared.masked().text(), prepared.snippet(), prepared.masked().count(),
                        prepared.masked().typesText(), prepared.fingerprint());
                chunkService.index(documentId, prepared.masked().text());
            });
            log.info("OCR of document {} recognized {} characters{} in {} ms", documentId,
                    recognized.text().length(), recognized.truncated() ? " (truncated)" : "",
                    System.currentTimeMillis() - started);
            return Outcome.DONE;
        } catch (RuntimeException | java.io.IOException ex) {
            return fail(documentId, ex.toString());
        }
    }

    private Outcome fail(Long documentId, String reason) {
        log.warn("OCR of document {} failed ({}); kept without recognized text, marked for review", documentId, reason);
        try {
            transaction.executeWithoutResult(status ->
                    documentRepository.changeOcrStatus(documentId, OcrStatus.RUNNING, OcrStatus.FAILED));
        } catch (RuntimeException ex) {
            log.warn("Could not mark the OCR of document {} as failed: {}", documentId, ex.toString());
        }
        return Outcome.FAILED;
    }
}
