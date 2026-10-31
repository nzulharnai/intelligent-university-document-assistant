package com.university.docassistant.service;

import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.entity.OcrStatus;
import com.university.docassistant.util.PersonalDataMasker;
import com.university.docassistant.util.PersonalDataMasker.Exemptions;
import com.university.docassistant.util.PersonalDataMasker.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

/**
 * US-09: the masked copy of a document's text - what goes to the external AI, into Q&A chunks, the snippet and
 * full-text search. The original stays in documents.extracted_text. With app.privacy.enabled=false the copy is the
 * text unchanged, so every consumer still reads the same column.
 */
@Service
@RequiredArgsConstructor
public class DocumentPrivacyService {

    private final PrivacyProperties properties;

    /**
     * Masks a document's text. The course teacher's own e-mail and phone stay readable (a syllabus names its teacher
     * and how to reach them); either may be null. When they change, CoursePrivacyRemask masks the course again.
     */
    public Result mask(String text, String teacherEmail, String teacherPhone) {
        if (!properties.isEnabled()) {
            return new Result(text, 0, Set.of());
        }
        Set<String> emails = teacherEmail == null ? Set.of() : Set.of(teacherEmail);
        Set<String> phones = teacherPhone == null ? Set.of() : Set.of(teacherPhone);
        return PersonalDataMasker.mask(text, new Exemptions(Set.copyOf(properties.publicEmailDomains()), emails, phones));
    }

    /**
     * Everything stored from a document's text besides the original: the masked copy with its counts, the snippet
     * cut from it, and the fingerprint of the masking settings. Used for uploaded and for OCR-recognized text alike.
     */
    public PreparedText prepare(String text, String teacherEmail, String teacherPhone) {
        Result masked = mask(text, teacherEmail, teacherPhone);
        return new PreparedText(masked, DocumentParserService.snippet(masked.text()), fingerprint());
    }

    public record PreparedText(Result masked, String snippet, String fingerprint) {
    }

    /**
     * A file name as the AI and students see it ("Иванов_ИИН_990101300013.pdf" happens): personal data masked. The
     * course teacher and ADMIN see the real name.
     */
    public String maskFileName(String fileName) {
        return maskFreeText(fileName);
    }

    /** A Q&A question goes to the external AI: masked like a document's text (the answer is shown as it comes). */
    public String maskQuestion(String question) {
        return maskFreeText(question);
    }

    private String maskFreeText(String value) {
        if (!properties.isEnabled() || value == null) {
            return value;
        }
        return PersonalDataMasker.mask(value,
                new Exemptions(Set.copyOf(properties.publicEmailDomains()), Set.of(), Set.of())).text();
    }

    /**
     * "Personal data unknown": a PDF or image without readable text (its one-line text, the stored snippet, is
     * missing or shorter than {@value DocumentOcrService#MIN_TEXT_LENGTH} characters) and no successful OCR - queued,
     * running, failed, or OCR not available here. pii_count was computed from no text, so it says nothing; the
     * original stays closed to students like one with personal data. After a successful OCR (DONE) pii_count counts.
     */
    public boolean isPiiUnknown(String contentType, OcrStatus ocrStatus, String textSnippet) {
        boolean scan = "application/pdf".equals(contentType) || (contentType != null && contentType.startsWith("image/"));
        boolean noText = textSnippet == null || textSnippet.length() < DocumentOcrService.MIN_TEXT_LENGTH;
        return properties.isEnabled() && scan && noText && ocrStatus != OcrStatus.DONE;
    }

    /**
     * What a stored masked text was made with: the masker version and the settings. documents.pii_scan keeps it;
     * rows with another value are masked again at startup (DocumentPrivacyBackfill), so turning privacy on or
     * changing the public domains takes effect for old documents too. The domains are sorted (and de-duplicated)
     * first, so listing the same domains in another order changes nothing.
     */
    public String fingerprint() {
        String settings = "v" + PersonalDataMasker.VERSION + ";" + (properties.isEnabled() ? "on" : "off") + ";"
                + String.join(",", new TreeSet<>(properties.publicEmailDomains()));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(settings.getBytes(StandardCharsets.UTF_8));
            return "v" + PersonalDataMasker.VERSION + "-" + HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is always available", ex);
        }
    }
}
