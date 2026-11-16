package com.university.docassistant.dto;

/**
 * Full extracted text of a document; {@code text} is null when nothing could be extracted
 * (a scan without a text layer, a password-protected or corrupted file, an unsupported format).
 * {@code truncated} means the text hit the 100 000-character limit and the rest of the file was not read.
 * {@code masked}: the text is the copy with personal data replaced by placeholders - always for a student and for
 * anyone who asked for it (?masked=true, where Q&A source offsets point); otherwise the course teacher and ADMIN get
 * the original. {@code piiCount}: how many items the masked copy hides.
 */
public record DocumentTextResponse(
        Long id,
        String originalFileName,
        boolean hasExtractedText,
        boolean truncated,
        String text,
        boolean masked,
        int piiCount
) {
}
