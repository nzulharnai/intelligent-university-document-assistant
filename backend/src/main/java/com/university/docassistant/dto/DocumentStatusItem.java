package com.university.docassistant.dto;

import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.OcrStatus;

/**
 * One document of GET /api/v1/documents/status?ids=..., the batch form of {@link DocumentStatusResponse}.
 *
 * @param aiConfidence     the AI's confidence 0..1 when aiStatus is DONE and it gave one; null otherwise
 * @param assignmentsCount assignments found in this document by the deadline analysis
 */
public record DocumentStatusItem(
        Long id,
        AiStatus aiStatus,
        DocumentCategory category,
        Double aiConfidence,
        long assignmentsCount,
        /** US-07: text recognition of a scan (null when none is needed) and whether the text came from it. */
        OcrStatus ocrStatus,
        boolean textFromOcr
) {
}
