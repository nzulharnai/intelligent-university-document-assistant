package com.university.docassistant.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * Metadata of a file in the vault. The content itself lives as-is on disk under {@code storedFileName}.
 */
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_documents_course_id", columnList = "course_id"),
        @Index(name = "idx_documents_visibility", columnList = "visibility"),
        @Index(name = "idx_documents_owner_id", columnList = "owner_id"),
        @Index(name = "idx_documents_uploaded_at", columnList = "uploaded_at"),
        @Index(name = "idx_documents_batch_import_id", columnList = "batch_import_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    /** Random name on disk, so user-supplied names never reach the file system. */
    @Column(name = "stored_file_name", nullable = false, unique = true, length = 64)
    private String storedFileName;

    @Column(name = "content_type", nullable = false, length = 150)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    // The column defaults only backfill rows uploaded before categories existed; new rows always set both.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @ColumnDefault("'LECTURE'")
    private DocumentCategory category;

    /** Coarse type derived from {@code category} at upload; rows from before this column default to OTHER. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @ColumnDefault("'OTHER'")
    private DocumentType type;

    /**
     * Background AI classification state. Rows from before this column count as finished (DONE): Hibernate adds
     * the column with this default, like category and visibility above.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ai_status", nullable = false, length = 20)
    @ColumnDefault("'DONE'")
    private AiStatus aiStatus;

    /** Pre-existing rows get TEACHER_ONLY, which keeps their old access rule: the owner plus TEACHER/ADMIN. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @ColumnDefault("'TEACHER_ONLY'")
    private DocumentVisibility visibility;

    /** Every document belongs to a course (Moodle model); DocumentCourseMigration enforces NOT NULL on old schemas. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    @ToString.Exclude
    private Course course;

    /**
     * Text extracted from the file at upload, up to 100 000 characters; null if the file has no readable text.
     * Full-text search uses the generated column documents.text_tsv (see DocumentSearchSchema) built from it.
     */
    @Column(name = "extracted_text", columnDefinition = "TEXT")
    @ToString.Exclude
    private String extractedText;

    /**
     * First 200 characters of {@code extractedText} on one line, null exactly when there is no text.
     * Lists read this column instead of the full text, so a page of results stays small.
     */
    /**
     * US-09: the text with personal data replaced by placeholders ([ИИН скрыт], [телефон скрыт], ...). Everything that
     * leaves the database for the external AI, the Q&A chunks, the snippet and full-text search use this copy; the
     * original above stays for the course teacher and ADMIN. Null when there is no text.
     */
    @Column(name = "masked_text", columnDefinition = "TEXT")
    @Basic(fetch = FetchType.LAZY)
    private String maskedText;

    /** How many personal data items were masked, and of which kinds ("IIN,PHONE"; null when none). */
    @Column(name = "pii_count", nullable = false)
    @ColumnDefault("0")
    private int piiCount;

    @Column(name = "pii_types", length = 100)
    private String piiTypes;

    /** DocumentPrivacyService.fingerprint() the masked copy was made with; another value means "mask again". */
    @Column(name = "pii_scan", length = 64)
    private String piiScan;

    /**
     * The course teacher let students open the original file although it contains personal data (pii_count > 0).
     * Off by default: students then get only the masked text.
     */
    @Column(name = "original_allowed_for_students", nullable = false)
    @ColumnDefault("false")
    private boolean originalAllowedForStudents;

    /** US-07: text recognition of a scan; null when none is needed. FAILED = "Нужна проверка" for the teacher. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ocr_status", length = 20)
    private OcrStatus ocrStatus;

    /** The stored text was recognized by OCR, not read from a text layer. */
    @Column(name = "text_from_ocr", nullable = false)
    @ColumnDefault("false")
    private boolean textFromOcr;

    @Column(name = "text_snippet", length = 255)
    private String textSnippet;

    /** True when the file held more text than the 100 000-character limit and the rest was not stored. */
    @Column(name = "text_truncated", nullable = false)
    @ColumnDefault("false")
    private boolean textTruncated;

    /** Short AI summary of the document; null until an AI analysis has succeeded. */
    @Column(name = "ai_summary", columnDefinition = "TEXT")
    @ToString.Exclude
    private String aiSummary;

    /** Key topics found by the AI, one per line. */
    @Column(name = "ai_topics", columnDefinition = "TEXT")
    @ToString.Exclude
    private String aiTopics;

    /** When the last successful AI analysis finished; null if there was none. */
    @Column(name = "ai_analyzed_at")
    private LocalDateTime aiAnalyzedAt;

    /**
     * The last AI analysis covered only part of a long text (a chunk got no usable answer): its drafts may miss
     * assignments, "ИИ обработал документ не полностью" for the teacher. Cleared by the next complete analysis.
     */
    @Column(name = "ai_extraction_partial", nullable = false)
    @ColumnDefault("false")
    private boolean aiExtractionPartial;

    /** The ZIP import the document came from; null for single and multi-file uploads. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_import_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @ToString.Exclude
    private BatchImport batchImport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    @ToString.Exclude
    private User owner;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
    }
}
