package com.university.docassistant.controller;

import com.university.docassistant.dto.AssignmentDtos.AssignmentResponse;
import com.university.docassistant.dto.DocumentResponse;
import com.university.docassistant.dto.DocumentStatusItem;
import com.university.docassistant.dto.DocumentStatusResponse;
import com.university.docassistant.dto.AuthDtos.MessageResponse;
import com.university.docassistant.dto.DocumentAnalysisResponse;
import com.university.docassistant.dto.DocumentAnswerResponse;
import com.university.docassistant.dto.DocumentQuestionRequest;
import com.university.docassistant.dto.DocumentTextResponse;
import com.university.docassistant.dto.PageResponse;
import com.university.docassistant.dto.UploadDtos.BatchUploadResponse;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.exception.ApiException;
import com.university.docassistant.exception.ErrorCodes;
import com.university.docassistant.service.DocumentQnaService;
import com.university.docassistant.service.DocumentService;
import com.university.docassistant.service.DocumentService.DocumentContent;
import com.university.docassistant.util.SortValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    /** Types the browser renders without running scripts; anything else (HTML, SVG, ...) is never served inline. */
    private static final Set<String> INLINE_CONTENT_TYPES = Set.of(
            MediaType.APPLICATION_PDF_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_GIF_VALUE,
            "image/webp");

    private static final Set<String> SORTABLE_FIELDS = Set.of("uploadedAt", "originalFileName", "fileSize", "category");

    private final DocumentService documentService;
    private final DocumentQnaService documentQnaService;

    /**
     * Uploads for the course's teacher or ADMIN only (403 otherwise). Without {@code category} the AI classifier
     * picks the document type; SYLLABUS and ASSIGNMENT documents then get their deadlines extracted.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(@RequestParam("file") MultipartFile file,
                                                   @RequestParam(value = "category", required = false) DocumentCategory category,
                                                   @RequestParam(value = "visibility", required = false) DocumentVisibility visibility,
                                                   @RequestParam(value = "courseId", required = false) Long courseId,
                                                   @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                documentService.upload(file, category, visibility, courseId, principal.getUsername()));
    }

    /** Several files at once (US-04); unsupported or oversized ones are listed in {@code skipped}. */
    @PostMapping(value = "/upload-multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BatchUploadResponse> uploadMultiple(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "category", required = false) DocumentCategory category,
            @RequestParam(value = "visibility", required = false) DocumentVisibility visibility,
            @RequestParam(value = "courseId", required = false) Long courseId,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                documentService.uploadMultiple(files, category, visibility, courseId, principal.getUsername()));
    }

    /** A ZIP archive whose supported files become documents of the course (US-06). */
    @PostMapping(value = "/upload-batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BatchUploadResponse> uploadBatch(
            @RequestParam("file") MultipartFile archive,
            @RequestParam(value = "category", required = false) DocumentCategory category,
            @RequestParam(value = "visibility", required = false) DocumentVisibility visibility,
            @RequestParam(value = "courseId", required = false) Long courseId,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                documentService.uploadBatch(archive, category, visibility, courseId, principal.getUsername()));
    }

    /**
     * Documents of the user's courses, optionally only those of one course ({@code ?courseId=}); {@code ?needsReview=true}
     * keeps those the AI or the OCR failed on ("Требуют проверки"), for the course teacher and ADMIN.
     */
    @GetMapping
    public PageResponse<DocumentResponse> list(
            @RequestParam(value = "courseId", required = false) Long courseId,
            @RequestParam(value = "needsReview", defaultValue = "false") boolean needsReview,
            @PageableDefault(size = 20, sort = "uploadedAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserDetails principal) {
        return documentService.list(principal.getUsername(), courseId, needsReview,
                SortValidator.requireSortable(pageable, SORTABLE_FIELDS));
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return documentService.get(id, principal.getUsername());
    }

    /** Latest AI summary and topics of the document, and whether the current user may (re)start the analysis. */
    @GetMapping("/{id}/analysis")
    public DocumentAnalysisResponse analysis(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return documentService.analysis(id, principal.getUsername());
    }

    /** Full-text search in the extracted text; results are ordered by relevance, so {@code sort} is ignored. */
    @GetMapping("/search")
    public PageResponse<DocumentResponse> search(@RequestParam("query") String query,
                                                 @PageableDefault(size = 20) Pageable pageable,
                                                 @AuthenticationPrincipal UserDetails principal) {
        return documentService.search(query, principal.getUsername(), pageable);
    }

    @GetMapping("/{id}/text")
    public DocumentTextResponse text(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return documentService.text(id, principal.getUsername());
    }

    /** Queues a (re-)analysis; the new tasks replace the document's previous ones once it finishes. */
    @PostMapping("/{id}/analyze")
    public ResponseEntity<MessageResponse> analyze(@PathVariable Long id,
                                                   @AuthenticationPrincipal UserDetails principal) {
        documentService.requestAnalysis(id, principal.getUsername());
        return ResponseEntity.accepted().body(new MessageResponse("AI analysis started"));
    }

    /**
     * The same state for up to 50 documents at once ({@code ?ids=1,2,3}), for polling lists; documents the user may
     * not view are simply missing from the answer.
     */
    @GetMapping("/status")
    public List<DocumentStatusItem> statuses(@RequestParam(value = "ids", required = false) List<Long> ids,
                                             @AuthenticationPrincipal UserDetails principal) {
        return documentService.statuses(ids, principal.getUsername());
    }

    /** Background AI state of the document: aiStatus, category, confidence, number of assignments found in it. */
    @GetMapping("/{id}/status")
    public DocumentStatusResponse status(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return documentService.status(id, principal.getUsername());
    }

    /** Queues a new AI classification of the document (aiStatus becomes PENDING); 503 when the AI is unavailable. */
    @PostMapping("/{id}/reclassify")
    public ResponseEntity<MessageResponse> reclassify(@PathVariable Long id,
                                                      @AuthenticationPrincipal UserDetails principal) {
        documentService.reclassify(id, principal.getUsername());
        return ResponseEntity.accepted().body(new MessageResponse("AI classification started"));
    }

    /** Answers a question from this document's text, citing the fragments used; 503 when the AI is unavailable. */
    @PostMapping("/{id}/ask")
    public DocumentAnswerResponse ask(@PathVariable Long id,
                                      @Valid @RequestBody DocumentQuestionRequest request,
                                      @AuthenticationPrincipal UserDetails principal) {
        return documentQnaService.askDocument(id, request.question(), principal.getUsername());
    }

    /** Assignments the AI found in this document (each enrolled student has a task per assignment). */
    @GetMapping({"/{id}/assignments", "/{id}/tasks"})
    public PageResponse<AssignmentResponse> assignments(
            @PathVariable Long id,
            @PageableDefault(size = 20, sort = "deadline", direction = Sort.Direction.ASC) Pageable pageable,
            @AuthenticationPrincipal UserDetails principal) {
        return documentService.assignments(id, principal.getUsername(),
                SortValidator.requireSortable(pageable, AssignmentController.SORTABLE_FIELDS));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        DocumentContent result = documentService.download(id, principal.getUsername());
        Document document = result.document();

        // Always an attachment: user-supplied content (e.g. HTML) must never render inline on our origin.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(result.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getContentType()))
                .contentLength(contentLength(result.content()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(result.content());
    }

    @GetMapping("/{id}/view")
    public ResponseEntity<Resource> view(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        DocumentContent result = documentService.view(id, principal.getUsername());
        Document document = result.document();

        MediaType contentType = MediaType.parseMediaType(document.getContentType());
        if (!INLINE_CONTENT_TYPES.contains(contentType.getType() + "/" + contentType.getSubtype())) {
            throw ApiException.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.PREVIEW_NOT_AVAILABLE, "Preview is not available for this file type");
        }

        ContentDisposition disposition = ContentDisposition.inline()
                .filename(result.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(contentLength(result.content()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(result.content());
    }

    /** "Указать тип вручную": the teacher sets the category the AI could not find; MANAGE_COURSE, audited. */
    @PostMapping("/{id}/category")
    public DocumentResponse setCategory(@PathVariable Long id, @Valid @RequestBody SetCategoryRequest request,
                                        @AuthenticationPrincipal UserDetails principal) {
        return documentService.setCategory(id, request.category(), principal.getUsername());
    }

    /** "Повторить распознавание": re-queues the processing job of a scan whose OCR failed; MANAGE_COURSE, audited. */
    @PostMapping("/{id}/ocr/retry")
    public DocumentResponse retryOcr(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return documentService.retryOcr(id, principal.getUsername());
    }

    public record SetCategoryRequest(@NotNull DocumentCategory category) {
    }

    /** US-09: students may open the original although it contains personal data; MANAGE_COURSE, audited. */
    @PostMapping("/{id}/allow-original")
    public DocumentResponse allowOriginal(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return documentService.setOriginalAllowedForStudents(id, true, principal.getUsername());
    }

    /** US-09: students get only the masked text again (the default); MANAGE_COURSE, audited. */
    @PostMapping("/{id}/deny-original")
    public DocumentResponse denyOriginal(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return documentService.setOriginalAllowedForStudents(id, false, principal.getUsername());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        documentService.delete(id, principal.getUsername());
        return ResponseEntity.noContent().build();
    }

    private static long contentLength(Resource content) {
        try {
            return content.contentLength();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
