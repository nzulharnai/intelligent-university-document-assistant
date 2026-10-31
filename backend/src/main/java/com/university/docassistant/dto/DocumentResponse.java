package com.university.docassistant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.DocumentVisibility;
import com.university.docassistant.entity.OcrStatus;

import java.time.LocalDateTime;

/**
 * Document metadata for the vault list. {@code canDelete} and {@code isOwner} are computed for the requesting user
 * so the frontend does not have to duplicate the permission rules. The course fields are null for documents
 * not tied to a course. {@code textSnippet} holds the first 200 characters of the masked text (personal data
 * hidden); the full text is served by GET /api/v1/documents/{id}/text.
 */
public record DocumentResponse(
        Long id,
        String originalFileName,
        String contentType,
        long fileSize,
        String ownerEmail,
        String ownerFullName,
        LocalDateTime uploadedAt,
        boolean canDelete,
        DocumentCategory category,
        DocumentVisibility visibility,
        Long courseId,
        String courseTitle,
        String courseCode,
        boolean hasExtractedText,
        String textSnippet,
        // Explicit name: Jackson would otherwise serialize a boolean "isX" accessor as "x".
        @JsonProperty("isOwner") boolean isOwner,
        /** Category chosen by the AI classifier (null if picked by hand) and its confidence 0..1 (null if not given). */
        String aiCategory,
        Double aiConfidence,
        /** PENDING/PROCESSING while the AI classifies it in the background; see GET /documents/{id}/status. */
        AiStatus aiStatus,
        /**
         * US-09: personal data items masked in the text; their kinds ("IIN,PHONE") only for the course teacher and
         * ADMIN (null for a student); whether students may open the original file although it has personal data.
         */
        int piiCount,
        String piiTypes,
        boolean originalAllowedForStudents,
        /**
         * US-07: PENDING/RUNNING while the scan is being recognized, FAILED when it could not be ("Нужна проверка"),
         * DONE when the text came from OCR; null when no OCR was needed. {@code textFromOcr}: the text is recognized.
         */
        OcrStatus ocrStatus,
        boolean textFromOcr,
        /**
         * A scan whose text is not recognized (yet): it is unknown whether it holds personal data, so a student gets
         * the original only when the teacher allowed it ("Скан не проверен на личные данные").
         */
        boolean piiUnknown
) {
}
