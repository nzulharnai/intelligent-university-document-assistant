package com.university.docassistant.repository;

import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentClassification;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.OcrStatus;
import com.university.docassistant.entity.User;

import java.time.LocalDateTime;

/**
 * A document row for lists: everything a list shows, without the (up to 100 000 characters) extracted text.
 * Built by the constructor expressions in {@link DocumentRepository}.
 */
public record DocumentSummary(
        Long id,
        String originalFileName,
        String contentType,
        Long fileSize,
        DocumentCategory category,
        DocumentVisibility visibility,
        LocalDateTime uploadedAt,
        String textSnippet,
        Long ownerId,
        String ownerEmail,
        String ownerFullName,
        Long courseId,
        String courseTitle,
        String courseCode,
        /** What the AI classifier chose and how sure it was; null when the category was picked by hand. */
        String aiCategory,
        Double aiConfidence,
        AiStatus aiStatus,
        /** US-09: personal data items masked in the text, their kinds, and whether students may open the original. */
        int piiCount,
        String piiTypes,
        boolean originalAllowedForStudents,
        /** US-07: text recognition of a scan (null when none was needed), and whether the text came from it. */
        OcrStatus ocrStatus,
        boolean textFromOcr,
        /** US-16: the confirmed week and the AI's suggestion (DocumentService hides the latter from students). */
        Integer weekNumber,
        Integer suggestedWeek
) {
    /** Select list matching the canonical constructor; the queries alias Document as d, owner as o, course as c. */
    static final String SELECT = """
            SELECT new com.university.docassistant.repository.DocumentSummary(
                d.id, d.originalFileName, d.contentType, d.fileSize, d.category, d.visibility, d.uploadedAt,
                d.textSnippet, o.id, o.email, o.fullName, c.id, c.title, c.code, dc.categoryName, dc.confidenceScore,
                d.aiStatus, d.piiCount, d.piiTypes, d.originalAllowedForStudents, d.ocrStatus, d.textFromOcr,
                d.weekNumber, d.suggestedWeek)
            FROM Document d JOIN d.owner o LEFT JOIN d.course c
                LEFT JOIN DocumentClassification dc ON dc.document = d
            """;

    /** Without the AI classification; see {@link #of(Document, DocumentClassification)}. */
    public static DocumentSummary of(Document document) {
        return of(document, null);
    }

    public static DocumentSummary of(Document document, DocumentClassification classification) {
        User owner = document.getOwner();
        Course course = document.getCourse();
        return new DocumentSummary(
                document.getId(),
                document.getOriginalFileName(),
                document.getContentType(),
                document.getFileSize(),
                document.getCategory(),
                document.getVisibility(),
                document.getUploadedAt(),
                document.getTextSnippet(),
                owner.getId(),
                owner.getEmail(),
                owner.getFullName(),
                course != null ? course.getId() : null,
                course != null ? course.getTitle() : null,
                course != null ? course.getCode() : null,
                classification != null ? classification.getCategoryName() : null,
                classification != null ? classification.getConfidenceScore() : null,
                document.getAiStatus(),
                document.getPiiCount(),
                document.getPiiTypes(),
                document.isOriginalAllowedForStudents(),
                document.getOcrStatus(),
                document.isTextFromOcr(),
                document.getWeekNumber(),
                document.getSuggestedWeek());
    }
}
