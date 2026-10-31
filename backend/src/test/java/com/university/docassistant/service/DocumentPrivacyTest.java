package com.university.docassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.university.docassistant.config.AiProperties;
import com.university.docassistant.config.DocumentPrivacyBackfill;
import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.BatchImportRepository;
import com.university.docassistant.repository.DocumentClassificationRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentRepository.AnalysisSource;
import com.university.docassistant.repository.DocumentRepository.ClassificationSource;
import com.university.docassistant.repository.DocumentRepository.PrivacySource;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import com.university.docassistant.service.DocumentParserService.ExtractedText;
import com.university.docassistant.service.storage.StorageService;
import com.university.docassistant.util.PersonalDataMasker.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-09: every document gets a masked copy of its text, and only that copy reaches the AI, the Q&A chunks and the
 * snippet. The original stays in extracted_text.
 */
class DocumentPrivacyTest {

    private static final String IIN = "990101300013";
    private static final String TEACHER_EMAIL = "o.kim@gmail.com";
    private static final String RAW = """
            Силлабус курса «Алгоритмы» (CS101). Преподаватель: Ким О., o.kim@gmail.com; деканат: dean@sdu.edu.kz.
            Староста: Иванов А., ИИН 990101300013, тел. +7 701 123 45 67, ivanov@mail.ru.
            Лабораторная работа №1 — до 20.10.2026 23:59, 10 баллов.""";

    private final DocumentPrivacyService privacy =
            new DocumentPrivacyService(new PrivacyProperties(true, List.of("sdu.edu.kz")));

    private static void assertNoPersonalData(String text) {
        assertThat(text).doesNotContain(IIN).doesNotContain("701 123 45 67").doesNotContain("ivanov@mail.ru");
    }

    @Nested
    class Masking {

        @Test
        void teachersEmailPublicDomainsAndSyllabusDataStay() {
            Result result = privacy.mask(RAW, TEACHER_EMAIL, null);

            assertNoPersonalData(result.text());
            assertThat(result.text()).contains(TEACHER_EMAIL, "dean@sdu.edu.kz", "20.10.2026 23:59", "CS101",
                    "[ИИН скрыт]", "[телефон скрыт]", "[e-mail скрыт]");
            assertThat(result.count()).isEqualTo(3);
            assertThat(result.typesText()).isEqualTo("EMAIL,IIN,PHONE");
        }

        @Test
        void disabledPrivacyKeepsTheTextAsItIs() {
            DocumentPrivacyService off = new DocumentPrivacyService(new PrivacyProperties(false, List.of()));
            assertThat(off.mask(RAW, null, null).text()).isEqualTo(RAW);
            assertThat(off.mask(RAW, null, null).count()).isZero();
            assertThat(off.maskFileName("Иванов_" + IIN + ".pdf")).isEqualTo("Иванов_" + IIN + ".pdf");
        }

        @Test
        void missingSettingMeansEnabled() {
            assertThat(new PrivacyProperties(null, null).isEnabled()).isTrue();
        }

        @Test
        void fingerprintIsStableAndFollowsTheSettings() {
            String fingerprint = privacy.fingerprint();
            assertThat(new DocumentPrivacyService(new PrivacyProperties(true, List.of(" SDU.edu.kz "))).fingerprint())
                    .isEqualTo(fingerprint);
            assertThat(new DocumentPrivacyService(new PrivacyProperties(true, List.of())).fingerprint())
                    .isNotEqualTo(fingerprint);
            assertThat(new DocumentPrivacyService(new PrivacyProperties(false, List.of("sdu.edu.kz"))).fingerprint())
                    .isNotEqualTo(fingerprint);
            assertThat(fingerprint).hasSizeLessThanOrEqualTo(64);
        }
    }

    /** Upload: the masked copy is stored next to the original, and the snippet and chunks come from it. */
    @Nested
    class Upload {

        private final DocumentRepository documents = mock(DocumentRepository.class);
        private final AccessControlService access = mock(AccessControlService.class);
        private final StorageService storage = mock(StorageService.class);
        private final DocumentParserService parser = mock(DocumentParserService.class);
        private final DocumentChunkService chunks = mock(DocumentChunkService.class);
        private final AiClientService ai = mock(AiClientService.class);
        private final DocumentService service = new DocumentService(documents, mock(TaskRepository.class),
                mock(AssignmentRepository.class), access, storage, parser, mock(DocumentAnalysisService.class), ai,
                chunks, mock(AuditService.class), mock(DocumentClassificationService.class),
                mock(DocumentProcessingService.class), mock(BatchImportRepository.class), mock(AssignmentService.class),
                privacy);

        @BeforeEach
        void setUp() {
            User teacher = User.builder().id(2L).email(TEACHER_EMAIL).role(Role.ROLE_TEACHER).build();
            Course course = Course.builder().id(10L).title("Algorithms").teacher(teacher).build();
            when(access.currentUser(TEACHER_EMAIL)).thenReturn(teacher);
            when(access.authorizeCourse(teacher, 10L, Permission.UPLOAD_DOCUMENT)).thenReturn(course);
            when(parser.extractText(any(InputStream.class), anyString(), anyString()))
                    .thenReturn(new ExtractedText(RAW, false));
            when(storage.store(any(byte[].class))).thenReturn("stored-name");
            when(documents.save(any(Document.class))).thenAnswer(invocation -> {
                Document document = invocation.getArgument(0);
                document.setId(100L);
                return document;
            });
        }

        @Test
        void originalIsKeptAndEverythingBuiltFromTheTextUsesTheMaskedCopy() {
            service.upload(new MockMultipartFile("file", "syllabus.txt", "text/plain",
                    "Силлабус".getBytes(StandardCharsets.UTF_8)), DocumentCategory.SYLLABUS, null, 10L, TEACHER_EMAIL);

            ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
            verify(documents).save(saved.capture());
            Document document = saved.getValue();
            assertThat(document.getExtractedText()).isEqualTo(RAW);
            assertNoPersonalData(document.getMaskedText());
            assertThat(document.getMaskedText()).contains(TEACHER_EMAIL, "[ИИН скрыт]");
            assertThat(document.getPiiCount()).isEqualTo(3);
            assertThat(document.getPiiTypes()).isEqualTo("EMAIL,IIN,PHONE");
            assertThat(document.getPiiScan()).isEqualTo(privacy.fingerprint());
            assertThat(document.getTextSnippet()).isEqualTo(DocumentParserService.snippet(document.getMaskedText()));
            assertNoPersonalData(document.getTextSnippet());
            verify(chunks).index(100L, document.getMaskedText());
        }
    }

    /** The AI jobs read the masked column (the source records select d.maskedText): nothing personal is sent. */
    @Nested
    class AiReceivesMaskedText {

        private static final long ID = 100L;
        private final DocumentRepository documents = mock(DocumentRepository.class);
        private final AiClientService ai = mock(AiClientService.class);
        private final String masked = privacy.mask(RAW, TEACHER_EMAIL, null).text();

        @BeforeEach
        void setUp() {
            when(ai.isConfigured()).thenReturn(true);
            when(ai.chatJson(anyString(), anyString())).thenReturn(Optional.empty());
            when(ai.chat(anyString(), anyString())).thenReturn(Optional.empty());
        }

        @Test
        void classificationSendsTheMaskedTextAndAMaskedFileName() {
            DocumentClassificationService classifier = new DocumentClassificationService(ai,
                    mock(DocumentClassificationRepository.class), documents, new ObjectMapper(), privacy);
            DocumentProcessingService processing = new DocumentProcessingService(documents,
                    mock(DocumentClassificationRepository.class), classifier, mock(DocumentAnalysisService.class),
                    mock(AuditService.class), mock(PlatformTransactionManager.class), mock(DocumentOcrService.class));
            when(documents.changeAiStatus(ID, AiStatus.PENDING, AiStatus.PROCESSING)).thenReturn(1);
            when(documents.findClassificationSource(ID)).thenReturn(Optional.of(
                    new ClassificationSource(ID, "Иванов_ИИН_" + IIN + ".pdf", masked, TEACHER_EMAIL)));

            processing.process(ID);

            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(ai).chatJson(anyString(), message.capture());
            assertNoPersonalData(message.getValue());
            assertThat(message.getValue()).contains("[ИИН скрыт]", "Иванов_ИИН_[ИИН скрыт].pdf");
        }

        @Test
        void deadlineAnalysisSendsTheMaskedText() {
            AiProperties properties = new AiProperties(true, "key", "model", "http://localhost",
                    Duration.ofSeconds(1), Duration.ofSeconds(1), 60_000, 1, 1, 1, 1);
            AiTaskExtractionService extraction = new AiTaskExtractionService(ai, properties, new ObjectMapper());
            DocumentAnalysisService analysis = new DocumentAnalysisService(documents, mock(AssignmentService.class),
                    extraction, mock(PlatformTransactionManager.class));
            when(documents.findAnalysisSource(ID)).thenReturn(Optional.of(
                    new AnalysisSource(ID, DocumentCategory.SYLLABUS, masked, 2L, 10L)));

            analysis.analyzeNow(ID, true);

            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(ai).chat(anyString(), message.capture());
            assertNoPersonalData(message.getValue());
            assertThat(message.getValue()).contains("[телефон скрыт]", "20.10.2026");
        }
    }

    /** Startup backfill against an in-memory stand-in for the documents table. */
    @Nested
    class Backfill {

        private final DocumentRepository documents = mock(DocumentRepository.class);
        private final DocumentChunkService chunks = mock(DocumentChunkService.class);
        private final Map<Long, Row> table = new TreeMap<>();

        /** The columns the backfill reads and writes. */
        private static final class Row {
            final long id;
            final String text;
            final String teacherEmail;
            String masked;
            String snippet;
            int count;
            String types;
            String scan;

            Row(long id, String text, String teacherEmail) {
                this.id = id;
                this.text = text;
                this.teacherEmail = teacherEmail;
            }
        }

        @BeforeEach
        void setUp() {
            table.put(1L, new Row(1L, RAW, TEACHER_EMAIL));
            table.put(2L, new Row(2L, "Лекция 1. Контакты: 87011234567", null));
            table.put(3L, new Row(3L, null, null)); // No text: nothing to mask.
            when(documents.findPrivacyBatch(anyString(), anyLong(), any(Pageable.class))).thenAnswer(invocation -> {
                String fingerprint = invocation.getArgument(0);
                long afterId = invocation.getArgument(1);
                Pageable page = invocation.getArgument(2);
                List<PrivacySource> batch = new ArrayList<>();
                for (Row row : table.values()) {
                    if (row.id > afterId && row.text != null && !fingerprint.equals(row.scan)
                            && batch.size() < page.getPageSize()) {
                        batch.add(new PrivacySource(row.id, row.text, row.teacherEmail, null, row.scan));
                    }
                }
                return batch;
            });
            when(documents.updatePrivacy(anyLong(), any(), any(), anyInt(), any(), anyString())).thenAnswer(invocation -> {
                Row row = table.get((Long) invocation.getArgument(0));
                row.masked = invocation.getArgument(1);
                row.snippet = invocation.getArgument(2);
                row.count = invocation.getArgument(3);
                row.types = invocation.getArgument(4);
                row.scan = invocation.getArgument(5);
                return 1;
            });
        }

        private DocumentPrivacyBackfill backfill(DocumentPrivacyService service) {
            return new DocumentPrivacyBackfill(documents, service, chunks, mock(PlatformTransactionManager.class));
        }

        @Test
        void masksEveryDocumentWithTextAndRebuildsItsChunksFromTheMaskedCopy() {
            assertThat(backfill(privacy).backfill()).isEqualTo(2);

            Row syllabus = table.get(1L);
            assertNoPersonalData(syllabus.masked);
            assertThat(syllabus.masked).contains(TEACHER_EMAIL);
            assertThat(syllabus.count).isEqualTo(3);
            assertThat(syllabus.types).isEqualTo("EMAIL,IIN,PHONE");
            assertNoPersonalData(syllabus.snippet);
            assertThat(table.get(2L).masked).isEqualTo("Лекция 1. Контакты: [телефон скрыт]");
            assertThat(table.get(3L).scan).isNull();
            verify(chunks).index(1L, syllabus.masked);
            verify(chunks).index(2L, table.get(2L).masked);
        }

        @Test
        void secondRunDoesNothing() {
            backfill(privacy).backfill();

            assertThat(backfill(privacy).backfill()).isZero();
            verify(chunks, times(2)).index(anyLong(), any()); // Only from the first run.
        }

        @Test
        void changedSettingsMaskEverythingAgain() {
            backfill(privacy).backfill();
            DocumentPrivacyService noPublicDomains = new DocumentPrivacyService(new PrivacyProperties(true, List.of()));

            assertThat(backfill(noPublicDomains).backfill()).isEqualTo(2);

            assertThat(table.get(1L).masked).doesNotContain("dean@sdu.edu.kz");
            verify(chunks, times(2)).index(eq(1L), any());
        }
    }
}
