package com.university.docassistant.service;

import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.DocumentAnalysisResponse;
import com.university.docassistant.dto.DocumentResponse;
import com.university.docassistant.dto.DocumentStatusItem;
import com.university.docassistant.dto.DocumentTextResponse;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.dto.UploadDtos.BatchUploadResponse;
import com.university.docassistant.dto.UploadDtos.SkippedFile;
import com.university.docassistant.dto.UploadDtos.UploadedDocument;
import com.university.docassistant.dto.BatchImportResponse;
import com.university.docassistant.dto.DocumentStatusResponse;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.AssignmentStatus;
import com.university.docassistant.entity.BatchImport;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.OcrStatus;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentClassification;
import com.university.docassistant.entity.DocumentType;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.BatchImportRepository;
import com.university.docassistant.repository.BatchImportRepository.BatchImportRow;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentSummary;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.AccessControlService.Scope;
import com.university.docassistant.security.Permission;
import com.university.docassistant.service.DocumentParserService.ExtractedText;
import com.university.docassistant.service.storage.StorageService;
import com.university.docassistant.util.PersonalDataMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Documents always belong to a course, and every access goes through {@link AccessControlService}:
 * view - the course's enrolled students (not TEACHER_ONLY documents), its teacher, ADMIN;
 * upload, delete, analyze - the course's teacher or ADMIN. Documents from before this rule were moved into an
 * archive course without teacher or students (DocumentCourseMigration), so they stay reachable by ADMIN only.
 * Students also never see a document while the AI is still classifying it (PENDING/PROCESSING), nor one the AI or
 * the OCR failed on until the teacher sets its type by hand (AccessControlService.isHiddenFromStudents).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final int MAX_CONTENT_TYPE_LENGTH = 150;
    private static final int MAX_QUERY_LENGTH = 200;
    /** Per document, whether uploaded alone, with others or inside a ZIP archive. */
    static final long MAX_DOCUMENT_BYTES = 20L * 1024 * 1024;
    private static final int MAX_FILES_PER_REQUEST = 50;
    /** Formats accepted by the multi-file and ZIP uploads, with the content type stored for each. */
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "pdf", MediaType.APPLICATION_PDF_VALUE,
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "doc", "application/msword",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "txt", MediaType.TEXT_PLAIN_VALUE,
            "png", MediaType.IMAGE_PNG_VALUE,
            "jpg", MediaType.IMAGE_JPEG_VALUE,
            "jpeg", MediaType.IMAGE_JPEG_VALUE);

    private final DocumentRepository documentRepository;
    private final TaskRepository taskRepository;
    private final AssignmentRepository assignmentRepository;
    private final AccessControlService access;
    private final StorageService storageService;
    private final DocumentParserService documentParserService;
    private final DocumentAnalysisService documentAnalysisService;
    private final AiClientService aiClientService;
    private final DocumentChunkService documentChunkService;
    private final AuditService auditService;
    private final DocumentClassificationService classificationService;
    private final DocumentProcessingService documentProcessingService;
    private final BatchImportRepository batchImportRepository;
    private final AssignmentService assignmentService;
    private final DocumentPrivacyService privacyService;

    public record DocumentContent(Document document, Resource content, String fileName) {
    }

    /**
     * One file. Without {@code category} the AI classifies it in the background (see {@link #store}).
     * For the course teacher or ADMIN only, checked before the file is read. 400 when the content does not match
     * the extension (FileTypeValidator).
     */
    public DocumentResponse upload(MultipartFile file, DocumentCategory category, DocumentVisibility visibility,
                                   Long courseId, String email) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.UPLOAD_EMPTY, "Файл пустой");
        }
        UploadTarget target = authorizeUpload(courseId, visibility, email);
        String fileName = sanitizeFileName(file.getOriginalFilename());
        byte[] content = readUpload(file, fileName);
        FileTypeValidator.Result type = FileTypeValidator.validate(content, fileName);
        if (!type.isValid()) {
            throw ApiException.badRequest(type.code(), type.problem());
        }
        return store(target, fileName, content, type.detectedType(), category, null).document();
    }

    /**
     * Several files in one request (US-04). Unsupported, empty or oversized files, and files whose content does not
     * match their extension, are skipped and reported; the rest become documents of the course, each classified by
     * the AI unless {@code category} is given.
     */
    public BatchUploadResponse uploadMultiple(List<MultipartFile> files, DocumentCategory category,
                                              DocumentVisibility visibility, Long courseId, String email) {
        UploadTarget target = authorizeUpload(courseId, visibility, email);
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.UPLOAD_EMPTY, "Не выбрано ни одного файла");
        }
        if (files.size() > MAX_FILES_PER_REQUEST) {
            throw ApiException.badRequest(ErrorCodes.UPLOAD_TOO_MANY_FILES, "За один раз можно загрузить не больше " + MAX_FILES_PER_REQUEST + " файлов");
        }
        List<UploadedDocument> uploaded = new ArrayList<>();
        List<SkippedFile> skipped = new ArrayList<>();
        for (MultipartFile file : files) {
            String fileName = sanitizeFileName(file.getOriginalFilename());
            String problem = file.isEmpty() ? "Пустой файл"
                    : !isAllowedDocument(fileName) ? "Неподдерживаемый формат файла"
                    : file.getSize() > MAX_DOCUMENT_BYTES ? "Файл больше " + MAX_DOCUMENT_BYTES / (1024 * 1024) + " МБ"
                    : null;
            if (problem != null) {
                skipped.add(new SkippedFile(fileName, problem));
                continue;
            }
            byte[] content = readUpload(file, fileName);
            FileTypeValidator.Result type = FileTypeValidator.validate(content, fileName);
            if (!type.isValid()) {
                skipped.add(new SkippedFile(fileName, type.problem()));
                continue;
            }
            uploaded.add(store(target, fileName, content, type.detectedType(), category, null));
        }
        return new BatchUploadResponse(uploaded, skipped, null);
    }

    /**
     * A ZIP archive (US-06): its supported files become documents of the course, the rest is reported as skipped.
     * Rights are checked before the archive is opened; see ZipArchiveExtractor for the Zip Slip and size guards.
     * Every import is recorded as a BatchImport, also when all its files were skipped; its processedFiles is the
     * number of documents actually created, written even when a later file fails.
     */
    public BatchUploadResponse uploadBatch(MultipartFile archive, DocumentCategory category,
                                           DocumentVisibility visibility, Long courseId, String email) {
        UploadTarget target = authorizeUpload(courseId, visibility, email);
        if (archive == null || archive.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.UPLOAD_EMPTY, "Архив пустой");
        }
        String archiveName = sanitizeFileName(archive.getOriginalFilename());
        if (!archiveName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw ApiException.badRequest(ErrorCodes.UPLOAD_UNSUPPORTED_TYPE, "Ожидается ZIP-архив (.zip)");
        }
        ZipArchiveExtractor.Result extracted;
        try {
            extracted = ZipArchiveExtractor.extract(readUpload(archive, archiveName),
                    DocumentService::isAllowedDocument, MAX_DOCUMENT_BYTES);
        } catch (IllegalStateException ex) {
            throw ApiException.badRequest(ErrorCodes.UPLOAD_INVALID_ARCHIVE, ex.getMessage());
        }
        // System entries (__MACOSX, .DS_Store, folders) are not counted; skipped ones are.
        BatchImport batchImport = batchImportRepository.save(BatchImport.builder()
                .course(target.course())
                .faculty(target.owner())
                .archiveName(archiveName)
                .totalFiles(extracted.files().size() + extracted.skipped().size())
                .processedFiles(0)
                .build());
        List<UploadedDocument> uploaded = new ArrayList<>();
        // Entries the extractor refused, plus files whose content does not match their extension.
        List<SkippedFile> skipped = new ArrayList<>(extracted.skipped());
        try {
            for (ZipArchiveExtractor.ExtractedFile file : extracted.files()) {
                String fileName = sanitizeFileName(file.fileName());
                FileTypeValidator.Result type = FileTypeValidator.validate(file.content(), fileName);
                if (!type.isValid()) {
                    skipped.add(new SkippedFile(fileName, type.problem()));
                    continue;
                }
                uploaded.add(store(target, fileName, file.content(), type.detectedType(), category, batchImport));
            }
        } finally {
            batchImport.setProcessedFiles(uploaded.size());
            batchImportRepository.save(batchImport);
            auditService.record(target.owner().getEmail(), AuditService.BATCH_IMPORTED,
                    "Archive \"" + archiveName + "\" (import " + batchImport.getId() + ") into course " + courseId
                            + ": " + uploaded.size() + " of " + batchImport.getTotalFiles() + " file(s) imported, "
                            + skipped.size() + " skipped");
        }
        log.info("User {} imported {} document(s) from archive '{}' into course {} ({} skipped, import {})",
                target.owner().getEmail(), uploaded.size(), archiveName, courseId, skipped.size(),
                batchImport.getId());
        return new BatchUploadResponse(uploaded, skipped, batchImport.getId());
    }

    /**
     * The course's ZIP imports, newest first, each with how many of its documents the AI is still classifying;
     * for the course's teacher or ADMIN. Two queries per page, whatever its size.
     */
    @Transactional(readOnly = true)
    public PageResponse<BatchImportResponse> imports(Long courseId, String email, Pageable pageable) {
        access.authorizeCourse(access.currentUser(email), courseId, Permission.MANAGE_COURSE);
        Page<BatchImportRow> rows = batchImportRepository.findRowsByCourseId(courseId,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        Map<Long, Long> pending = new HashMap<>();
        if (rows.hasContent()) {
            for (Object[] row : batchImportRepository.countPendingByBatchImportIds(
                    rows.getContent().stream().map(BatchImportRow::id).toList())) {
                pending.put((Long) row[0], (Long) row[1]);
            }
        }
        return PageResponse.of(rows.map(row -> new BatchImportResponse(row.id(), row.archiveName(), row.totalFiles(),
                row.processedFiles(), row.facultyName(), row.createdAt(), pending.getOrDefault(row.id(), 0L))));
    }

    private record UploadTarget(User owner, Course course, DocumentVisibility visibility) {
    }

    /**
     * Only the course's teacher or ADMIN may add documents to it; students get 403 before anything is read; they
     * upload only as a submission for their task (SubmissionService).
     */
    private UploadTarget authorizeUpload(Long courseId, DocumentVisibility visibility, String email) {
        User owner = access.currentUser(email);
        if (!Permission.granted(owner.getRole(), Permission.UPLOAD_DOCUMENT)) {
            auditService.record(owner.getEmail(), AuditService.ACCESS_DENIED, "UPLOAD_DOCUMENT on course " + courseId);
            if (owner.isTeacherRequested()) {
                throw ApiException.forbidden(ErrorCodes.TEACHER_ROLE_PENDING, CourseService.TEACHER_ROLE_PENDING_MESSAGE);
            }
            throw ApiException.forbidden(ErrorCodes.UPLOAD_FORBIDDEN, "Загружать документы курса может только его преподаватель или деканат. "
                    + "Студенты прикрепляют файлы как решение к своей задаче (раздел «Мои задачи»)");
        }
        if (courseId == null) {
            throw ApiException.badRequest(ErrorCodes.COURSE_REQUIRED, "Выберите курс: каждый документ принадлежит курсу");
        }
        Course course = access.authorizeCourse(owner, courseId, Permission.UPLOAD_DOCUMENT);
        // PUBLIC_ALL no longer reaches beyond the course; it is stored as what it now means.
        DocumentVisibility effective = visibility == null || visibility == DocumentVisibility.PUBLIC_ALL
                ? DocumentVisibility.COURSE_STUDENTS
                : visibility;
        return new UploadTarget(owner, course, effective);
    }

    /** Analyzing exam answers into assignments would hand their titles and deadlines to students as tasks. */
    static final String BEING_PROCESSED = "ИИ или распознавание текста ещё обрабатывают документ. Подождите, пока "
            + "они закончат.";
    static final String OCR_UNAVAILABLE = "Распознавание сканов не настроено на сервере.";
    static final String OCR_NOT_FAILED = "Повторить можно только неудавшееся распознавание; сейчас оно не требуется "
            + "или уже идёт.";

    static final String ORIGINAL_RESTRICTED = "В оригинале документа есть персональные данные, преподаватель не открыл "
            + "его студентам. Доступна текстовая версия, где эти данные скрыты.";
    static final String ORIGINAL_UNCHECKED = "Скан ещё не проверен на личные данные. Оригинал откроется после "
            + "распознавания текста или когда преподаватель разрешит доступ.";

    static final String EXAM_ANSWERS_NOT_ANALYZED = "Ответы к экзамену не анализируются на задания";

    /**
     * Stores one file as a document of the target course: text extraction, Q&A chunks, and then, without waiting for
     * any AI call:
     * <ul>
     *   <li>category chosen by the uploader - stored as is (MANUAL); a SYLLABUS or ASSIGNMENT gets the background
     *       assignment extraction;</li>
     *   <li>no category, AI configured - stored as OTHER/PENDING and queued for DocumentProcessingService, which
     *       classifies it in the background; students do not see it until then;</li>
     *   <li>no category, AI not configured - stored as OTHER/FAILED.</li>
     * </ul>
     * An EXAM_ANSWERS document is always stored TEACHER_ONLY, whatever the uploader picked.
     */
    private UploadedDocument store(UploadTarget target, String fileName, byte[] content, String declaredType,
                                   DocumentCategory chosenCategory, BatchImport batchImport) {
        if (content.length > MAX_DOCUMENT_BYTES) {
            throw ApiException.badRequest(ErrorCodes.UPLOAD_TOO_LARGE, "Файл «" + fileName + "» больше " + MAX_DOCUMENT_BYTES / (1024 * 1024) + " МБ");
        }
        String contentType = declaredType == null ? contentTypeOf(fileName) : sanitizeContentType(declaredType);

        // A failed parse only leaves the document without text; it never fails the upload.
        ExtractedText extracted = extractText(content, fileName, contentType);
        // US-09: personal data is masked before anything is built from the text. The snippet, the Q&A chunks, search
        // and every AI job use the masked copy; the original stays in extracted_text. The teacher's contacts stay.
        User teacher = target.course().getTeacher();
        DocumentPrivacyService.PreparedText prepared = privacyService.prepare(extracted.text(),
                teacher == null ? null : teacher.getEmail(), teacher == null ? null : teacher.getPhone());
        PersonalDataMasker.Result masked = prepared.masked();
        // US-07: a scan (PDF/image without text) is queued for OCR; it runs in the background job, never here.
        boolean needsOcr = documentProcessingService.needsOcr(contentType, extracted.text());

        DocumentCategory category = chosenCategory != null ? chosenCategory : DocumentCategory.OTHER;
        AiStatus aiStatus = chosenCategory != null ? AiStatus.MANUAL
                : aiClientService.isConfigured() ? AiStatus.PENDING
                : AiStatus.FAILED;
        DocumentVisibility visibility = target.visibility();
        boolean visibilityForced = category == DocumentCategory.EXAM_ANSWERS
                && visibility != DocumentVisibility.TEACHER_ONLY;
        if (visibilityForced) {
            visibility = DocumentVisibility.TEACHER_ONLY;
        }

        String storedFileName = storageService.store(content);
        Document document;
        try {
            document = documentRepository.save(Document.builder()
                    .originalFileName(fileName)
                    .storedFileName(storedFileName)
                    .contentType(contentType)
                    .fileSize((long) content.length)
                    .extractedText(extracted.text())
                    .maskedText(masked.text())
                    .piiCount(masked.count())
                    .piiTypes(masked.typesText())
                    .piiScan(prepared.fingerprint())
                    .textSnippet(prepared.snippet())
                    .ocrStatus(needsOcr ? OcrStatus.PENDING : null)
                    .textTruncated(extracted.truncated())
                    .category(category)
                    .type(DocumentType.fromCategory(category))
                    .visibility(visibility)
                    .aiStatus(aiStatus)
                    .batchImport(batchImport)
                    .course(target.course())
                    .owner(target.owner())
                    .build());
            log.info("User {} uploaded document {} to course {} ({} bytes, {}, AI {}, {}, {} chars of text{})",
                    target.owner().getEmail(), document.getId(), target.course().getId(), content.length, category,
                    aiStatus, visibility,
                    extracted.text() == null ? 0 : extracted.text().length(),
                    extracted.truncated() ? ", truncated" : "");
        } catch (RuntimeException ex) {
            storageService.delete(storedFileName);
            throw ex;
        }
        auditService.record(target.owner().getEmail(), AuditService.DOCUMENT_UPLOADED,
                "Document " + document.getId() + " \"" + fileName + "\" ("
                        + (aiStatus == AiStatus.PENDING ? "AI classification pending" : category.toString())
                        + ") in course " + target.course().getId()
                        + (visibilityForced ? ", visibility forced to TEACHER_ONLY" : "")
                        + (masked.count() > 0 ? ", personal data masked: " + masked.count() + " (" + masked.typesText() + ")" : "")
                        + (needsOcr ? ", queued for OCR" : ""));

        if (extracted.text() != null) {
            try {
                documentChunkService.index(document.getId(), masked.text());
            } catch (RuntimeException ex) {
                // Q&A is unavailable for this document until DocumentChunkBackfill retries at the next start.
                log.warn("Could not chunk document {} for Q&A: {}", document.getId(), ex.toString());
            }
        }

        // The row is committed by now (upload runs without a transaction), so the background jobs can read it.
        // The classification job recognizes a scan first; a scan with a known category gets an OCR job of its own,
        // which runs the deadline analysis afterwards.
        if (aiStatus == AiStatus.PENDING) {
            submitProcessing(document.getId());
        } else if (needsOcr) {
            submitOcr(document.getId());
        } else if (extracted.text() != null && DocumentAnalysisService.AUTO_ANALYZED.contains(category)
                && aiClientService.isConfigured()) {
            submitAnalysis(document.getId(), false);
        }
        // aiClassified stays false here: the AI has not chosen anything yet when the upload answers.
        return new UploadedDocument(toResponse(DocumentSummary.of(document), target.owner(), true), false);
    }

    /**
     * "Указать тип вручную": the course teacher (or ADMIN) sets the category the AI could not find, e.g. for a scan
     * whose text was not recognized. Same rules as an AI result: EXAM_ANSWERS become TEACHER_ONLY (rule A) and are
     * never analyzed (rule B); a SYLLABUS or ASSIGNMENT with text gets its deadline analysis. 409 while the AI or OCR
     * still works on the document. Audited.
     */
    @Transactional
    public DocumentResponse setCategory(Long id, DocumentCategory category, String email) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, id, Permission.MANAGE_COURSE);
        if (isBeingProcessed(document)) {
            throw ApiException.conflict(ErrorCodes.DOCUMENT_BEING_PROCESSED, BEING_PROCESSED);
        }
        documentRepository.updateClassification(id, category, DocumentType.fromCategory(category), AiStatus.MANUAL);
        classificationService.clearResult(id);
        boolean forced = category == DocumentCategory.EXAM_ANSWERS && documentRepository.forceTeacherOnly(id) > 0;
        auditService.record(user.getEmail(), AuditService.DOCUMENT_CATEGORY_SET, "Document " + id + " \""
                + document.getOriginalFileName() + "\" set to " + category + " by hand"
                + (forced ? ", visibility forced to TEACHER_ONLY" : ""));
        if (document.getMaskedText() != null && DocumentAnalysisService.AUTO_ANALYZED.contains(category)
                && aiClientService.isConfigured()) {
            afterCommit(() -> submitAnalysis(id, false));
        }
        return get(id, email);
    }

    /**
     * "Повторить распознавание": queues the processing job again for a scan whose OCR failed. A category the AI
     * chose is chosen again from the new text (the document is PENDING meanwhile, hidden from students like any
     * document being classified); a category chosen by hand stays and only the OCR and deadline analysis run.
     * For the course teacher or ADMIN; 503 when OCR cannot run here, 409 when the OCR did not fail. Audited.
     */
    @Transactional
    public DocumentResponse retryOcr(Long id, String email) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, id, Permission.MANAGE_COURSE);
        if (!documentProcessingService.ocrAvailable()) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.OCR_UNAVAILABLE, OCR_UNAVAILABLE);
        }
        if (isBeingProcessed(document) || documentRepository.changeOcrStatus(id, OcrStatus.FAILED, OcrStatus.PENDING) == 0) {
            throw ApiException.conflict(ErrorCodes.OCR_NOT_FAILED, OCR_NOT_FAILED);
        }
        boolean reclassify = document.getAiStatus() != AiStatus.MANUAL && aiClientService.isConfigured();
        if (reclassify) {
            documentRepository.setAiStatus(id, AiStatus.PENDING);
        }
        auditService.record(user.getEmail(), AuditService.OCR_RETRY_REQUESTED, "Document " + id + " \""
                + document.getOriginalFileName() + "\"" + (reclassify ? ", to be classified again" : ""));
        // Submitted only after commit, otherwise the job could read the old statuses.
        afterCommit(() -> {
            if (reclassify) {
                submitProcessing(id);
            } else {
                submitOcr(id);
            }
        });
        return get(id, email);
    }

    private static boolean isBeingProcessed(Document document) {
        return document.getAiStatus() == AiStatus.PENDING || document.getAiStatus() == AiStatus.PROCESSING
                || document.getOcrStatus() == OcrStatus.PENDING || document.getOcrStatus() == OcrStatus.RUNNING;
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /** A full queue leaves the document queued for OCR (ocr_status PENDING); OcrStartup queues it at the next start. */
    private void submitOcr(Long documentId) {
        try {
            documentProcessingService.ocrAsync(documentId);
        } catch (TaskRejectedException ex) {
            log.warn("OCR of document {} not queued, it stays pending: {}", documentId, ex.getMessage());
        }
    }

    /** False when the AI queue is full: the document stays PENDING and AiProcessingRecovery queues it at the next start. */
    private boolean submitProcessing(Long documentId) {
        try {
            documentProcessingService.processAsync(documentId);
            return true;
        } catch (TaskRejectedException ex) {
            log.warn("AI classification of document {} not queued, it stays PENDING: {}", documentId, ex.getMessage());
            return false;
        }
    }

    /**
     * Where the background AI work on a document stands, for polling after an upload or a reclassify;
     * for everyone who can view the document.
     */
    @Transactional(readOnly = true)
    public DocumentStatusResponse status(Long id, String email) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, id, Permission.VIEW_DOCUMENT);
        Double confidence = document.getAiStatus() == AiStatus.DONE
                ? classificationService.findResult(id).map(DocumentClassification::getConfidenceScore).orElse(null)
                : null;
        // A student sees the document, so this is the teacher of its course or ADMIN when not a student.
        AssignmentCounts counts = assignmentCounts(List.of(id), user.getRole() != Role.ROLE_STUDENT)
                .getOrDefault(id, AssignmentCounts.NONE);
        return new DocumentStatusResponse(document.getAiStatus(), document.getCategory(), confidence,
                counts.total(), counts.drafts(), document.getOcrStatus(), document.isTextFromOcr());
    }

    /** Assignments found in a document: all of them, and of those the DRAFTs (both without drafts for a student). */
    private record AssignmentCounts(long total, long drafts) {
        static final AssignmentCounts NONE = new AssignmentCounts(0, 0);
    }

    private Map<Long, AssignmentCounts> assignmentCounts(List<Long> documentIds, boolean withDrafts) {
        Map<Long, AssignmentCounts> counts = new HashMap<>();
        for (Object[] row : assignmentRepository.countByStatusForSourceDocumentIds(documentIds)) {
            boolean draft = row[1] == AssignmentStatus.DRAFT;
            if (draft && !withDrafts) {
                continue;
            }
            long count = (Long) row[2];
            counts.merge((Long) row[0], new AssignmentCounts(count, draft ? count : 0),
                    (a, b) -> new AssignmentCounts(a.total() + b.total(), a.drafts() + b.drafts()));
        }
        return counts;
    }

    static final int MAX_STATUS_IDS = 50;

    /**
     * Status of several documents at once, for polling lists: one query for the rows (access decided in Java with the
     * same rule as AccessControlService.can) and one grouped count of their assignments. Ids the user may not view -
     * other courses, TEACHER_ONLY, still-classifying or failed documents for a student, unknown ids - are left out silently,
     * so the answer reveals nothing about them. At most {@value #MAX_STATUS_IDS} ids.
     */
    @Transactional(readOnly = true)
    public List<DocumentStatusItem> statuses(List<Long> ids, String email) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.size() > MAX_STATUS_IDS) {
            throw ApiException.badRequest(ErrorCodes.TOO_MANY_IDS,
                    "Не больше " + MAX_STATUS_IDS + " документов за один запрос");
        }
        if (distinct.isEmpty()) {
            return List.of();
        }
        Scope scope = access.scope(access.currentUser(email));
        List<DocumentRepository.StatusRow> visible = documentRepository.findStatusRows(distinct).stream()
                .filter(row -> AccessControlService.canView(scope, row.courseId(), row.visibility(), row.aiStatus(),
                        row.ocrStatus()))
                .toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        // includeTeacherOnly: the teacher of these courses or ADMIN, who also review the drafts.
        Map<Long, AssignmentCounts> counts = assignmentCounts(
                visible.stream().map(DocumentRepository.StatusRow::id).toList(), scope.includeTeacherOnly());
        return visible.stream()
                .map(row -> {
                    AssignmentCounts found = counts.getOrDefault(row.id(), AssignmentCounts.NONE);
                    return new DocumentStatusItem(row.id(), row.aiStatus(), row.category(),
                            row.aiStatus() == AiStatus.DONE ? row.confidence() : null, found.total(), found.drafts(),
                            row.ocrStatus(), row.textFromOcr());
                })
                .toList();
    }

    /**
     * Runs the AI classification of the document again in the background, also for one whose category was chosen by
     * hand; for the course teacher or ADMIN. Nothing is deleted: if the category stays SYLLABUS/ASSIGNMENT, the new
     * deadline analysis keeps matching assignments. A document already being classified is left to that job.
     * 503 when the AI is not configured, or when its queue is full (the document then stays PENDING until the next
     * start).
     */
    @Transactional
    public void reclassify(Long id, String email) {
        User user = access.currentUser(email);
        access.authorizeDocument(user, id, Permission.ANALYZE_DOCUMENT);
        if (!aiClientService.isConfigured()) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AI_NOT_CONFIGURED, "AI analysis is not configured");
        }
        if (documentRepository.findAiStatusById(id).orElse(null) == AiStatus.PROCESSING) {
            return;
        }
        documentRepository.setAiStatus(id, AiStatus.PENDING);
        log.info("User {} requested AI reclassification of document {}", user.getEmail(), id);
        // Submitted only after commit, otherwise the job could read the status before it is PENDING.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (!submitProcessing(id)) {
                    // Reaches the caller; the document stays PENDING and AiProcessingRecovery queues it at next start.
                    throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AI_QUEUE_FULL, "AI analysis queue is full, try again later");
                }
            }
        });
    }

    private static byte[] readUpload(MultipartFile file, String fileName) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read upload '" + fileName + "'", ex);
        }
    }

    static boolean isAllowedDocument(String fileName) {
        return ALLOWED_TYPES.containsKey(extensionOf(fileName));
    }

    static String contentTypeOf(String fileName) {
        return ALLOWED_TYPES.getOrDefault(extensionOf(fileName), MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Queues an AI analysis that replaces the assignments found in the document; for the course teacher or ADMIN.
     * Throws 400 for EXAM_ANSWERS documents and when there is no text, 503 when the AI is not configured or the
     * analysis queue is full.
     */
    @Transactional(readOnly = true)
    public void requestAnalysis(Long id, String email) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, id, Permission.ANALYZE_DOCUMENT);
        if (document.getCategory() == DocumentCategory.EXAM_ANSWERS) {
            throw ApiException.badRequest(ErrorCodes.AI_EXAM_ANSWERS_NOT_ANALYZED, EXAM_ANSWERS_NOT_ANALYZED);
        }
        if (!aiClientService.isConfigured()) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AI_NOT_CONFIGURED, "AI analysis is not configured");
        }
        if (document.getTextSnippet() == null) {
            throw ApiException.badRequest(ErrorCodes.AI_NO_TEXT, "The document has no extracted text to analyze");
        }
        if (!submitAnalysis(id, true)) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AI_QUEUE_FULL, "AI analysis queue is full, try again later");
        }
        log.info("User {} requested AI analysis of document {}", user.getEmail(), id);
    }

    /**
     * The document's latest AI summary and topics, plus what the current user can do about it, so the UI can offer
     * "analyze" only when it would be accepted.
     */
    @Transactional(readOnly = true)
    public DocumentAnalysisResponse analysis(Long id, String email) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, id, Permission.VIEW_DOCUMENT);
        List<String> topics = document.getAiTopics() == null ? List.of() : List.of(document.getAiTopics().split("\n"));
        return new DocumentAnalysisResponse(
                document.getAiSummary(),
                topics,
                document.getAiAnalyzedAt(),
                document.getTextSnippet() != null,
                aiClientService.isConfigured(),
                // Mirrors requestAnalysis, so the UI never offers an analysis that would be rejected.
                document.getCategory() != DocumentCategory.EXAM_ANSWERS
                        && access.can(user, document, Permission.ANALYZE_DOCUMENT),
                DocumentAnalysisService.AUTO_ANALYZED.contains(document.getCategory()));
    }

    /** Metadata of one document, as in the list. */
    @Transactional(readOnly = true)
    public DocumentResponse get(Long id, String email) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, id, Permission.VIEW_DOCUMENT);
        return toResponse(DocumentSummary.of(document, classificationService.findResult(id).orElse(null)), user,
                access.can(user, document, Permission.DELETE_DOCUMENT));
    }

    /** The document if the user may view it; 404 or 403 otherwise. Owner and course are loaded. */
    @Transactional(readOnly = true)
    public Document getViewable(Long id, String email) {
        return access.authorizeDocument(access.currentUser(email), id, Permission.VIEW_DOCUMENT);
    }

    /** Assignments the AI found in a document; visible to everyone who can view the document. */
    @Transactional(readOnly = true)
    public PageResponse<AssignmentResponse> assignments(Long id, String email, Pageable pageable) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, id, Permission.VIEW_DOCUMENT);
        boolean realName = access.can(user, document, Permission.MANAGE_COURSE);
        return PageResponse.of(assignmentRepository.findViewsBySourceDocumentId(id, pageable)
                .map(row -> realName ? row : row.withSourceDocumentName(fileName(row.sourceDocumentName(), false))));
    }

    /** False when the executor's queue is full; the document then simply stays without generated assignments. */
    private boolean submitAnalysis(Long documentId, boolean manual) {
        try {
            if (manual) {
                documentAnalysisService.reanalyzeAsync(documentId);
            } else {
                documentAnalysisService.analyzeAndCreateTasksAsync(documentId);
            }
            return true;
        } catch (TaskRejectedException ex) {
            log.warn("AI analysis of document {} not queued: {}", documentId, ex.getMessage());
            return false;
        }
    }

    /**
     * Documents of the user's courses; with {@code courseId}, of that course only (403 if it is not theirs).
     * {@code needsReview} ("Требуют проверки"): only those hidden from students because the AI or the OCR failed;
     * with {@code courseId} for its teacher or ADMIN only (403 otherwise). Without a course a student gets nothing
     * from it, since such documents are never in a student's scope.
     */
    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> list(String email, Long courseId, boolean needsReview, Pageable pageable) {
        User user = access.currentUser(email);
        if (courseId != null) {
            access.authorizeCourse(user, courseId, needsReview ? Permission.MANAGE_COURSE : Permission.VIEW_COURSE);
        }
        Scope scope = access.scope(user);
        Page<DocumentSummary> documents;
        if (scope.allCourses()) {
            documents = documentRepository.findAllSummaries(courseId, needsReview, pageable);
        } else if (scope.isEmpty()) {
            documents = Page.empty(pageable);
        } else {
            documents = documentRepository.findSummariesInCourses(scope.courseIds(), scope.includeTeacherOnly(),
                    courseId, needsReview, pageable);
        }
        Set<Long> managed = managedCourseIds(user, scope);
        return PageResponse.of(documents.map(document -> toResponse(document, user, canDelete(document, user, managed))));
    }

    /** Full-text search over the masked text (personal data finds nothing), best match first, within the user's courses. */
    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> search(String query, String email, Pageable pageable) {
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.SEARCH_QUERY_INVALID, "Search query must not be empty");
        }
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw ApiException.badRequest(ErrorCodes.SEARCH_QUERY_INVALID, "Search query must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        User user = access.currentUser(email);
        Scope scope = access.scope(user);
        if (scope.isEmpty()) {
            return PageResponse.of(Page.empty(pageable));
        }

        Page<Long> ids = documentRepository.searchVisibleIds(trimmed, scope.allCourses(), scope.courseIdsParam(),
                scope.includeTeacherOnly(), PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        // The second query loads only this page's rows; the relevance order of the ids is then restored.
        Map<Long, DocumentSummary> byId = documentRepository.findSummariesByIdIn(ids.getContent()).stream()
                .collect(Collectors.toMap(DocumentSummary::id, Function.identity()));
        Set<Long> managed = managedCourseIds(user, scope);
        List<DocumentResponse> content = ids.getContent().stream()
                .map(byId::get)
                .filter(Objects::nonNull) // Deleted between the two queries.
                .map(summary -> toResponse(summary, user, canDelete(summary, user, managed)))
                .toList();
        return PageResponse.of(new PageImpl<>(content, ids.getPageable(), ids.getTotalElements()));
    }

    /**
     * The document's text: the original for the course teacher and ADMIN, the masked copy (US-09) for everyone else.
     * A student never receives the original text, whatever the file permission says.
     */
    @Transactional(readOnly = true)
    public DocumentTextResponse text(Long id, String email) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, id, Permission.VIEW_DOCUMENT);
        boolean original = access.can(user, document, Permission.MANAGE_COURSE);
        String text = original ? document.getExtractedText() : maskedText(document);
        return new DocumentTextResponse(document.getId(), fileName(document.getOriginalFileName(), original),
                text != null, document.isTextTruncated(), text, !original, document.getPiiCount());
    }

    /** The stored masked copy; masked now if the startup backfill has not reached this document yet. */
    private String maskedText(Document document) {
        if (document.getMaskedText() != null || document.getExtractedText() == null) {
            return document.getMaskedText();
        }
        User teacher = document.getCourse() == null ? null : document.getCourse().getTeacher();
        return privacyService.mask(document.getExtractedText(), teacher == null ? null : teacher.getEmail(),
                teacher == null ? null : teacher.getPhone()).text();
    }

    /** The original file as an attachment; see {@link #openOriginal}. */
    @Transactional(readOnly = true)
    public DocumentContent download(Long id, String email) {
        return openOriginal(id, email, "download");
    }

    /** The original file for the inline preview; see {@link #openOriginal}. */
    @Transactional(readOnly = true)
    public DocumentContent view(Long id, String email) {
        return openOriginal(id, email, "view");
    }

    /**
     * The original file. When its text contains personal data (pii_count > 0), or it is a scan not yet checked for
     * personal data (DocumentPrivacyService.isPiiUnknown), a student gets it only if the course teacher allowed it
     * (403 PII_ORIGINAL_RESTRICTED otherwise); every opening of such an original - by the teacher, ADMIN or an allowed
     * student - is audited as PII_ORIGINAL_VIEWED.
     */
    private DocumentContent openOriginal(Long id, String email, String how) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, id, Permission.VIEW_DOCUMENT);
        boolean piiUnknown = privacyService.isPiiUnknown(document.getContentType(), document.getOcrStatus(),
                document.getTextSnippet());
        boolean managesCourse = access.can(user, document, Permission.MANAGE_COURSE);
        if (document.getPiiCount() > 0 || piiUnknown) {
            if (!managesCourse && !document.isOriginalAllowedForStudents()) {
                auditService.record(user.getEmail(), AuditService.ACCESS_DENIED, "Original of document " + id
                        + " (" + how + "): " + (piiUnknown ? "scan not checked for personal data" : "contains personal data"));
                throw ApiException.forbidden(ErrorCodes.PII_ORIGINAL_RESTRICTED,
                        piiUnknown ? ORIGINAL_UNCHECKED : ORIGINAL_RESTRICTED);
            }
            auditService.record(user.getEmail(), AuditService.PII_ORIGINAL_VIEWED, "Document " + id + " \""
                    + document.getOriginalFileName() + "\" (" + how + ", "
                    + (piiUnknown ? "scan not checked for personal data"
                        : document.getPiiCount() + " personal data item(s): " + document.getPiiTypes()) + ")");
        }
        // The name goes into Content-Disposition: masked like everywhere else for those who do not manage the course.
        return new DocumentContent(document, storageService.load(document.getStoredFileName()),
                fileName(document.getOriginalFileName(), managesCourse));
    }

    /**
     * Lets the course's students open the original file although it contains personal data, or takes that back.
     * For the course teacher or ADMIN (MANAGE_COURSE); audited.
     */
    @Transactional
    public DocumentResponse setOriginalAllowedForStudents(Long id, boolean allowed, String email) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, id, Permission.MANAGE_COURSE);
        if (document.isOriginalAllowedForStudents() != allowed) {
            document.setOriginalAllowedForStudents(allowed);
            documentRepository.save(document);
            auditService.record(user.getEmail(),
                    allowed ? AuditService.PII_ORIGINAL_ALLOWED : AuditService.PII_ORIGINAL_DENIED,
                    "Document " + id + " \"" + document.getOriginalFileName() + "\" ("
                            + document.getPiiCount() + " personal data item(s))");
        }
        return toResponse(DocumentSummary.of(document, classificationService.findResult(id).orElse(null)), user,
                access.can(user, document, Permission.DELETE_DOCUMENT));
    }

    /**
     * Removes the document with the assignments found in it and their students' tasks. Assignments with student work
     * are kept instead, CLOSED and unlinked from the document (AssignmentService.releaseDocuments).
     */
    @Transactional
    public void delete(Long id, String email) {
        User user = access.currentUser(email);
        Document document = access.authorizeDocument(user, id, Permission.DELETE_DOCUMENT);
        assignmentService.releaseDocuments(List.of(id), user.getEmail());
        taskRepository.deleteByAssignmentSourceDocumentId(id);
        assignmentRepository.deleteBySourceDocumentId(id);
        taskRepository.deleteByDocumentId(id);
        documentRepository.delete(document);

        // Remove the file only once the row is really gone, so a rolled-back delete never loses content.
        String storedFileName = document.getStoredFileName();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storageService.delete(storedFileName);
            }
        });
        auditService.record(user.getEmail(), AuditService.DOCUMENT_DELETED,
                "Document " + id + " \"" + document.getOriginalFileName() + "\"");
        log.info("User {} deleted document {}", user.getEmail(), id);
    }

    private ExtractedText extractText(byte[] content, String fileName, String contentType) {
        return documentParserService.extractText(new ByteArrayInputStream(content), fileName, contentType);
    }

    /** Courses whose documents the user may delete: the taught ones for a TEACHER, none for a STUDENT. */
    private static Set<Long> managedCourseIds(User user, Scope scope) {
        return user.getRole() == Role.ROLE_TEACHER ? Set.copyOf(scope.courseIds()) : Set.of();
    }

    // Mirrors AccessControlService.can(..., DELETE_DOCUMENT) for list rows, which carry no course teacher.
    private static boolean canDelete(DocumentSummary document, User user, Set<Long> managedCourseIds) {
        return user.getRole() == Role.ROLE_ADMIN
                || (document.courseId() != null && managedCourseIds.contains(document.courseId()));
    }

    /**
     * {@code canDelete} is "manages the document's course" (its teacher or ADMIN): they see the real file name,
     * everyone else the name with personal data masked (US-09), as in the text tab.
     */
    private DocumentResponse toResponse(DocumentSummary document, User viewer, boolean canDelete) {
        return new DocumentResponse(
                document.id(),
                fileName(document.originalFileName(), canDelete),
                document.contentType(),
                document.fileSize(),
                document.ownerEmail(),
                document.ownerFullName(),
                document.uploadedAt(),
                canDelete,
                document.category(),
                document.visibility(),
                document.courseId(),
                document.courseTitle(),
                document.courseCode(),
                document.textSnippet() != null,
                document.textSnippet(),
                document.ownerId().equals(viewer.getId()),
                document.aiCategory(),
                document.aiConfidence(),
                document.aiStatus(),
                document.piiCount(),
                // Which kinds of personal data a document holds is itself a hint: only for the teacher and ADMIN.
                viewer.getRole() == Role.ROLE_STUDENT ? null : document.piiTypes(),
                document.originalAllowedForStudents(),
                document.ocrStatus(),
                document.textFromOcr(),
                privacyService.isPiiUnknown(document.contentType(), document.ocrStatus(), document.textSnippet()));
    }

    // Keeps only the last path segment (browsers may send "C:\...\name.pdf") and bounds the length.
    /** US-09: the real file name for the course teacher and ADMIN, personal data masked for everyone else. */
    private String fileName(String originalFileName, boolean real) {
        return real ? originalFileName : privacyService.maskFileName(originalFileName);
    }

    static String sanitizeFileName(String originalFileName) {
        String name = StringUtils.getFilename(StringUtils.cleanPath(
                originalFileName == null ? "" : originalFileName));
        name = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            name = "file";
        }
        return name.length() > MAX_FILE_NAME_LENGTH ? name.substring(name.length() - MAX_FILE_NAME_LENGTH) : name;
    }

    private static String sanitizeContentType(String contentType) {
        if (contentType == null || contentType.length() > MAX_CONTENT_TYPE_LENGTH) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        try {
            return MediaType.parseMediaType(contentType).toString();
        } catch (InvalidMediaTypeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }
}
