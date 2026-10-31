package com.university.docassistant.service;

import com.university.docassistant.config.OcrProperties;
import com.university.docassistant.config.PrivacyProperties;
import com.university.docassistant.entity.AiStatus;
import com.university.docassistant.entity.Course;
import com.university.docassistant.entity.Document;
import com.university.docassistant.entity.DocumentCategory;
import com.university.docassistant.entity.OcrStatus;
import com.university.docassistant.entity.Role;
import com.university.docassistant.entity.User;
import com.university.docassistant.repository.AssignmentRepository;
import com.university.docassistant.repository.BatchImportRepository;
import com.university.docassistant.repository.DocumentClassificationRepository;
import com.university.docassistant.repository.DocumentRepository;
import com.university.docassistant.repository.DocumentRepository.ClassificationSource;
import com.university.docassistant.repository.DocumentRepository.OcrSource;
import com.university.docassistant.repository.TaskRepository;
import com.university.docassistant.security.AccessControlService;
import com.university.docassistant.security.Permission;
import com.university.docassistant.service.DocumentOcrService.Outcome;
import com.university.docassistant.service.DocumentParserService.ExtractedText;
import com.university.docassistant.service.OcrEngine.OcrException;
import com.university.docassistant.service.OcrEngine.Recognized;
import com.university.docassistant.service.storage.StorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-07 with Tesseract stubbed: OCR in the background job only, through the same text path as an upload. */
class DocumentOcrTest {

    private static final long ID = 100L;
    private static final String IIN = "990101300013";
    private static final String SCANNED = "Силлабус курса «Алгоритмы». Староста: Иванов, ИИН " + IIN
            + ". Лабораторная работа №1 — до 20.10.2026.";

    private final OcrEngine engine = mock(OcrEngine.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final DocumentChunkService chunks = mock(DocumentChunkService.class);
    private final DocumentPrivacyService privacy = new DocumentPrivacyService(new PrivacyProperties(true, List.of()));
    private final DocumentOcrService ocr = new DocumentOcrService(engine, documents, storage, privacy, chunks,
            mock(PlatformTransactionManager.class));

    @BeforeEach
    void setUp() {
        when(engine.isAvailable()).thenReturn(true);
    }

    @Nested
    class Decision {

        @Test
        void onlyAPdfOrImageWithoutTextNeedsOcr() {
            assertThat(ocr.needsOcr("application/pdf", null)).isTrue();
            assertThat(ocr.needsOcr("application/pdf", "  12  ")).isTrue(); // A few stray characters of a scan.
            assertThat(ocr.needsOcr("image/png", null)).isTrue();
            assertThat(ocr.needsOcr("application/pdf", "Текст ".repeat(30))).isFalse();
            assertThat(ocr.needsOcr("application/vnd.openxmlformats-officedocument.wordprocessingml.document", null))
                    .isFalse();
        }

        @Test
        void disabledOcrIsNeverNeededAndNeverCalled() {
            when(engine.isAvailable()).thenReturn(false);

            assertThat(ocr.needsOcr("application/pdf", null)).isFalse();
            assertThat(ocr.runIfPending(ID)).isEqualTo(Outcome.UNAVAILABLE);
            verify(documents, never()).changeOcrStatus(anyLong(), any(), any());
            verifyNoRecognition();
        }

        @Test
        void disabledFlagMeansTheRealEngineIsUnavailableWithoutLookingForTesseract() {
            TikaOcrEngine off = new TikaOcrEngine(new OcrProperties(false, "C:/nowhere", null, null, null));
            assertThat(off.isAvailable()).isFalse();
        }
    }

    @Nested
    class Run {

        @BeforeEach
        void queued() throws Exception {
            when(documents.changeOcrStatus(ID, OcrStatus.PENDING, OcrStatus.RUNNING)).thenReturn(1);
            when(documents.findOcrSource(ID)).thenReturn(Optional.of(
                    new OcrSource(ID, "stored", "scan.pdf", "application/pdf", "teacher@uni.kz", null)));
            when(storage.load("stored")).thenReturn(new ByteArrayResource(new byte[]{1, 2, 3}));
        }

        @Test
        void recognizedTextIsStoredMaskedAndChunkedLikeAnUpload() throws Exception {
            when(engine.recognize(any(), eq("scan.pdf"), eq("application/pdf"))).thenReturn(new Recognized(SCANNED, false));

            assertThat(ocr.runIfPending(ID)).isEqualTo(Outcome.DONE);

            ArgumentCaptor<String> masked = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> snippet = ArgumentCaptor.forClass(String.class);
            verify(documents).updateRecognizedText(eq(ID), eq(SCANNED), eq(false), masked.capture(), snippet.capture(),
                    eq(1), eq("IIN"), eq(privacy.fingerprint()));
            assertThat(masked.getValue()).doesNotContain(IIN).contains("[ИИН скрыт]", "20.10.2026");
            assertThat(snippet.getValue()).doesNotContain(IIN);
            verify(chunks).index(ID, masked.getValue());
        }

        @Test
        void timeoutKeepsTheDocumentAndMarksItForReview() throws Exception {
            when(engine.recognize(any(), anyString(), anyString()))
                    .thenThrow(new OcrException("OCR took longer than 120 s", true, null));

            assertThat(ocr.runIfPending(ID)).isEqualTo(Outcome.FAILED);

            verify(documents).changeOcrStatus(ID, OcrStatus.RUNNING, OcrStatus.FAILED);
            verify(documents, never()).updateRecognizedText(anyLong(), any(), anyBoolean(), any(), any(), anyInt(), any(), any());
            verify(documents, never()).deleteById(any());
            verify(documents, never()).delete(any());
            verify(chunks, never()).index(anyLong(), any());
        }

        @Test
        void noTextRecognizedIsAFailureToo() throws Exception {
            when(engine.recognize(any(), anyString(), anyString())).thenReturn(new Recognized(null, false));

            assertThat(ocr.runIfPending(ID)).isEqualTo(Outcome.FAILED);
            verify(documents).changeOcrStatus(ID, OcrStatus.RUNNING, OcrStatus.FAILED);
        }

        @Test
        void anUnexpectedErrorNeverReachesTheWorker() throws Exception {
            when(storage.load("stored")).thenThrow(new IllegalStateException("disk gone"));

            assertThatCode(() -> assertThat(ocr.runIfPending(ID)).isEqualTo(Outcome.FAILED)).doesNotThrowAnyException();
            verify(documents).changeOcrStatus(ID, OcrStatus.RUNNING, OcrStatus.FAILED);
        }

        @Test
        void aDocumentNotQueuedIsLeftAlone() throws Exception {
            when(documents.changeOcrStatus(ID, OcrStatus.PENDING, OcrStatus.RUNNING)).thenReturn(0);

            assertThat(ocr.runIfPending(ID)).isEqualTo(Outcome.NOT_NEEDED);
            verifyNoRecognition();
        }
    }

    /** The background job: OCR -> (masking, chunks) -> classification -> deadline drafts. */
    @Nested
    class Job {

        private final DocumentOcrService ocrService = mock(DocumentOcrService.class);
        private final DocumentClassificationService classifier = mock(DocumentClassificationService.class);
        private final DocumentAnalysisService analysis = mock(DocumentAnalysisService.class);
        private final DocumentProcessingService processing = new DocumentProcessingService(documents,
                mock(DocumentClassificationRepository.class), classifier, analysis, mock(AuditService.class),
                mock(PlatformTransactionManager.class), ocrService);

        @Test
        void ocrRunsBeforeTheClassifierReadsTheText() {
            when(documents.changeAiStatus(ID, AiStatus.PENDING, AiStatus.PROCESSING)).thenReturn(1);
            when(documents.findClassificationSource(ID)).thenReturn(Optional.of(
                    new ClassificationSource(ID, "scan.pdf", "[ИИН скрыт] силлабус", "teacher@uni.kz")));
            when(classifier.classify(anyString(), anyString())).thenReturn(Optional.empty());

            processing.process(ID);

            InOrder order = inOrder(documents, ocrService, classifier);
            order.verify(documents).changeAiStatus(ID, AiStatus.PENDING, AiStatus.PROCESSING);
            order.verify(ocrService).runIfPending(ID);
            order.verify(documents).findClassificationSource(ID);
            order.verify(classifier).classify("scan.pdf", "[ИИН скрыт] силлабус");
        }

        @Test
        void aScanWithAKnownCategoryGetsItsDeadlineAnalysisOnlyAfterSuccessfulOcr() {
            when(ocrService.runIfPending(ID)).thenReturn(Outcome.DONE);
            processing.ocrAsync(ID); // Called directly: no Spring proxy, so it runs synchronously.
            verify(analysis).analyzeNow(ID, true);

            when(ocrService.runIfPending(101L)).thenReturn(Outcome.FAILED);
            processing.ocrAsync(101L);
            verify(analysis, never()).analyzeNow(eq(101L), anyBoolean());
        }
    }

    /** The upload only decides: it queues the scan and never runs OCR itself. */
    @Nested
    class Upload {

        private final AccessControlService access = mock(AccessControlService.class);
        private final DocumentParserService parser = mock(DocumentParserService.class);
        private final DocumentProcessingService processing = mock(DocumentProcessingService.class);
        private final DocumentAnalysisService analysis = mock(DocumentAnalysisService.class);
        private final AiClientService ai = mock(AiClientService.class);
        private final DocumentService service = new DocumentService(documents, mock(TaskRepository.class),
                mock(AssignmentRepository.class), access, storage, parser, analysis, ai, chunks,
                mock(AuditService.class), mock(DocumentClassificationService.class), processing,
                mock(BatchImportRepository.class), mock(AssignmentService.class), privacy);

        @BeforeEach
        void setUp() {
            User teacher = User.builder().id(2L).email("teacher@uni.kz").role(Role.ROLE_TEACHER).build();
            Course course = Course.builder().id(10L).title("Algorithms").teacher(teacher).build();
            when(access.currentUser("teacher@uni.kz")).thenReturn(teacher);
            when(access.authorizeCourse(teacher, 10L, Permission.UPLOAD_DOCUMENT)).thenReturn(course);
            when(parser.extractText(any(InputStream.class), anyString(), anyString())).thenReturn(ExtractedText.NONE);
            when(storage.store(any(byte[].class))).thenReturn("stored");
            when(documents.save(any(Document.class))).thenAnswer(invocation -> {
                Document document = invocation.getArgument(0);
                document.setId(ID);
                return document;
            });
            when(ai.isConfigured()).thenReturn(true);
            when(processing.needsOcr(eq("application/pdf"), any())).thenReturn(true);
        }

        @Test
        void scannedSyllabusIsQueuedForOcrAndItsAnalysisWaitsForTheText() throws Exception {
            service.upload(pdf(1), DocumentCategory.SYLLABUS, null, 10L, "teacher@uni.kz");

            ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
            verify(documents).save(saved.capture());
            assertThat(saved.getValue().getOcrStatus()).isEqualTo(OcrStatus.PENDING);
            verify(processing).ocrAsync(ID);
            verify(analysis, never()).analyzeAndCreateTasksAsync(any());
            verifyNoRecognition();
        }

        @Test
        void unclassifiedScanGoesThroughTheClassificationJobWhichRunsOcrFirst() throws Exception {
            service.upload(pdf(1), null, null, 10L, "teacher@uni.kz");

            verify(processing).processAsync(ID);
            verify(processing, never()).ocrAsync(any());
        }
    }

    @Nested
    class Engine {

        @Test
        void missingTesseractOrLanguagesAreListed() {
            assertThat(TikaOcrEngine.missingParts(false, Set.of(), "rus+kaz+eng")).containsExactly("tesseract executable");
            assertThat(TikaOcrEngine.missingParts(true, Set.of("eng", "rus", "osd"), "rus+kaz+eng"))
                    .containsExactly("language pack 'kaz'");
            assertThat(TikaOcrEngine.missingParts(true, Set.of("eng", "rus", "kaz"), "rus+kaz+eng")).isEmpty();
        }

        @Test
        void languageListOfTesseract55IsReadWithItsFolderInTheHeader() {
            // Tika's getLangs() returns nothing for this format, so the check parses it itself.
            String output = "List of available languages in \"C:\\Program Files\\Tesseract-OCR/tessdata/\" (4):\r\n"
                    + "eng\r\nkaz\r\nosd\r\nrus\r\n";
            assertThat(TikaOcrEngine.parseLanguageList(output)).containsExactly("eng", "kaz", "osd", "rus");
            assertThat(TikaOcrEngine.parseLanguageList("List of available languages (2):\nchi_sim\nrus\n"))
                    .containsExactly("chi_sim", "rus");
        }

        @Test
        void longPdfIsCutToTheFirstPages() throws Exception {
            byte[] cut = TikaOcrEngine.firstPages(pdf(25).getBytes(), 20);
            try (PDDocument document = PDDocument.load(cut)) {
                assertThat(document.getNumberOfPages()).isEqualTo(20);
            }
            byte[] shortPdf = pdf(3).getBytes();
            assertThat(TikaOcrEngine.firstPages(shortPdf, 20)).isSameAs(shortPdf);
            assertThat(TikaOcrEngine.firstPages(new byte[]{1, 2}, 20)).containsExactly(1, 2); // Not a PDF: as is.
        }

        @Test
        void tesseractPathMayNameTheExecutableOrItsFolder() {
            assertThat(TikaOcrEngine.executableFolder("C:/Program Files/Tesseract-OCR/tesseract.exe"))
                    .isEqualTo(new java.io.File("C:/Program Files/Tesseract-OCR").getPath());
            assertThat(TikaOcrEngine.executableFolder("C:/Program Files/Tesseract-OCR"))
                    .isEqualTo("C:/Program Files/Tesseract-OCR");
        }

        @Test
        void defaultsMatchTheConfigDocumentation() {
            OcrProperties defaults = new OcrProperties(null, null, null, null, null);
            assertThat(defaults.isEnabled()).isFalse();
            assertThat(defaults.languages()).isEqualTo("rus+kaz+eng");
            assertThat(defaults.timeout()).isEqualTo(Duration.ofSeconds(120));
            assertThat(defaults.maxPages()).isEqualTo(20);
        }
    }

    private void verifyNoRecognition() {
        try {
            verify(engine, never()).recognize(any(), any(), any());
        } catch (OcrException ex) {
            throw new AssertionError(ex);
        }
    }

    private static MockMultipartFile pdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.save(out);
            return new MockMultipartFile("file", "scan.pdf", "application/pdf", out.toByteArray());
        }
    }
}
