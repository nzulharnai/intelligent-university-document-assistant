package com.university.docassistant.dto;

/**
 * A fragment of a document the answer is based on. {@code fragment} is the number the answer cites as [n].
 * Fragments are cut from the masked copy of the text, so the offsets (end exclusive) point into the masked text:
 * GET /api/v1/documents/{id}/text?masked=true, which is also what a student gets without the parameter.
 * {@code courseId} and {@code courseTitle} are null for a legacy document without a course.
 */
public record SourceSnippetDto(
        int fragment,
        Long documentId,
        String documentName,
        Long courseId,
        String courseTitle,
        int chunkIndex,
        int startOffset,
        int endOffset,
        String text
) {
}
